package com.evilfly.client;

import com.evilfly.MinecraftForFlies;
import com.evilfly.fly.DrosophilaEntity;

import net.fabricmc.api.ClientModInitializer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinecraftForFliesClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft-for-flies-client");
    // Guarda a posição anterior de cada player para inferir "andando" (sem info do servidor)
    private static final Map<String, VecMoving> lastPlayerPos = new HashMap<>();

    // DTO simples só para guardar last x/z de um player
    static class VecMoving {
        double x, z;
    }

    private HttpServer server;

    @Override
    public void onInitializeClient() {
        // Renderer da Drosophila (precisa vir antes do servidor HTTP para visual)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                MinecraftForFlies.DROSOPHILA, FlyRenderer::new);

        System.out.println("[DrosophilaBrain] Inicializando interface biológica e servidor HTTP (porta 8080)...");
        try {
            // Bind explicitamente em 127.0.0.1 para evitar problemas de IPv6 no Windows
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
            server.createContext("/state", new StateHandler());
            server.createContext("/action", new ActionHandler());
            server.setExecutor(null);
            server.start();
            System.out.println("[DrosophilaBrain] Servidor HTTP pronto em http://127.0.0.1:8080");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Retorna a mosca ativa no mundo do client, pelo UUID compartilhado.
    private static DrosophilaEntity activeFly(Minecraft client) {
        if (client.level == null) return null;
        UUID uuid = MinecraftForFlies.activeFlyUuid;
        if (uuid != null) {
            for (DrosophilaEntity fly : client.level.getEntitiesOfClass(DrosophilaEntity.class,
                    new AABB(-3e7, -3e7, -3e7, 3e7, 3e7, 3e7))) {
                if (fly.getUUID().equals(uuid)) return fly;
            }
        }
        List<DrosophilaEntity> flies = client.level.getEntitiesOfClass(DrosophilaEntity.class,
                new AABB(-3e7, -3e7, -3e7, 3e7, 3e7, 3e7));
        return flies.isEmpty() ? null : flies.get(0);
    }

    static class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // A leitura do mundo (client.level, getEntitiesOfClass, getBlockState)
            // só é segura na render thread, dona do mundo. O Java HttpServer roda os
            // handlers em threads de worker; então delegamos a montagem do JSON à
            // render thread via CompletableFuture e aguardamos o resultado aqui,
            // com timeout para nunca travar se o client estiver encerrando.
            Minecraft client = Minecraft.getInstance();
            CompletableFuture<String> future = new CompletableFuture<>();
            client.execute(() -> future.complete(buildStateJson(client)));
            String json;
            try {
                json = future.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                json = "{}";
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }

        /** Monta o JSON sensorial; SÓ chamar na render thread. */
        private String buildStateJson(Minecraft client) {
            DrosophilaEntity fly = activeFly(client);
            String json = "{}";
            if (fly != null) {
                double x = fly.getX();
                double y = fly.getY();
                double z = fly.getZ();
                float health = fly.getHealth();
                boolean hurt = fly.hurtTime > 0;
                boolean flightAttempt = MinecraftForFlies.targetJumping && !fly.onGround();
                boolean inWater = fly.isInWater();
                // Distâncias de "sensor" por categoria. O SENSOR de ameaça (20 blocos) é
                // maior que o raio de ataque (8) para a mosca se orientar antes. O
                // territorial de players é 150 (raciocínio 8 = ataque melee apenas).
                final double SENSE_THREAT = 20.0;
                final double SENSE_ITEM = 12.0;

                // Grade sensorial 3x3x3 ensinando a mosca o que cada bloco é
                List<Map<String, String>> blockSensoryData = new ArrayList<>();
                BlockPos pos = fly.blockPosition();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos bp = pos.offset(dx, dy, dz);
                            BlockState state = client.level.getBlockState(bp);
                            Map<String, String> bInfo = new HashMap<>();
                            bInfo.put("x", String.valueOf(bp.getX()));
                            bInfo.put("y", String.valueOf(bp.getY()));
                            bInfo.put("z", String.valueOf(bp.getZ()));
                            bInfo.put("name", state.getBlock().getName().getString());
                            bInfo.put("solid", String.valueOf(!state.isAir()));
                            blockSensoryData.add(bInfo);
                        }
                    }
                }

                // Reconhecimento de entidades: mobs hostis = ameaças, players = amigos
                List<String> threats = new ArrayList<>();
                List<Map<String, String>> friends = new ArrayList<>();
                // Itens soltos no chão (para o cérebro decidir recolher) — agora com coordenadas
                List<Map<String, String>> items = new ArrayList<>();
                for (Entity e : client.level.getEntitiesOfClass(Entity.class,
                        fly.getBoundingBox().inflate(150.0), entity -> entity != fly)) {
                    double ds = e.distanceToSqr(fly);
                    if (e instanceof Monster) {
                        // SENSOR de ameaça — orienta a mosca antes do alcance de ataque.
                        if (ds < SENSE_THREAT * SENSE_THREAT) threats.add(e.getName().getString());
                    } else if (e instanceof Player) {
                        String pname = e.getName().getString();
                        Map<String, String> pf = new HashMap<>();
                        pf.put("name", pname);
                        pf.put("x", String.format(Locale.ROOT, "%.2f", e.getX()));
                        pf.put("y", String.format(Locale.ROOT, "%.2f", e.getY()));
                        pf.put("z", String.format(Locale.ROOT, "%.2f", e.getZ()));
                        pf.put("dist", String.format(Locale.ROOT, "%.1f", Math.sqrt(ds)));
                        // "andando?" = deslocou horizontalmente > 0.5 bloco desde o último /state
                        VecMoving prev = lastPlayerPos.get(pname);
                        double pdx = prev == null ? 0 : e.getX() - prev.x;
                        double pdz = prev == null ? 0 : e.getZ() - prev.z;
                        boolean moving = (pdx * pdx + pdz * pdz) > 0.25;
                        pf.put("moving", String.valueOf(moving));
                        lastPlayerPos.put(pname, new VecMoving() {{
                            x = e.getX(); z = e.getZ();
                        }});
                        friends.add(pf);
                    } else if (e instanceof ItemEntity) {
                        if (ds < SENSE_ITEM * SENSE_ITEM) {
                            Map<String, String> ii = new HashMap<>();
                            ii.put("name", e.getName().getString());
                            ii.put("x", String.format(Locale.ROOT, "%.2f", e.getX()));
                            ii.put("y", String.format(Locale.ROOT, "%.2f", e.getY()));
                            ii.put("z", String.format(Locale.ROOT, "%.2f", e.getZ()));
                            items.add(ii);
                        }
                    }
                }

                // Hora do dia (para o cérebro decidir dormir/peregrinar).
                long dayTime = client.level.getDayTime() % 24000L;
                String timeOfDay = dayTime < 11000L ? "day"
                        : dayTime < 13000L ? "dusk"
                        : dayTime < 23000L ? "night" : "dawn";
                String bedJson = MinecraftForFlies.bedPos == null ? "null"
                        : String.format(Locale.ROOT, "{\"x\":%.1f,\"y\":%.1f,\"z\":%.1f}",
                                MinecraftForFlies.bedPos.x, MinecraftForFlies.bedPos.y, MinecraftForFlies.bedPos.z);

                json = String.format(Locale.ROOT,
                        "{\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"health\":%.1f,\"hurt\":%b,\"flight_attempt\":%b,\"in_water\":%b,\"time_of_day\":\"%s\",\"bed\":%s,\"blocks\":%s,\"threats\":%s,\"friends\":%s,\"items\":%s,\"thoughts\":\"%s\"}",
                        x, y, z, health, hurt, flightAttempt, inWater,
                        timeOfDay, bedJson,
                        blocksToJson(blockSensoryData), listToJson(threats), friendsToJson(friends), itemsToJson(items),
                        MinecraftForFlies.lastThoughts.replace("\"", "\\\"").replace("\n", " "));
            }

            return json;
        }

        private String friendsToJson(List<Map<String, String>> friends) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < friends.size(); i++) {
                Map<String, String> f = friends.get(i);
                sb.append(String.format(Locale.ROOT,
                        "{\"name\":\"%s\",\"x\":%s,\"y\":%s,\"z\":%s,\"moving\":%s,\"dist\":%s}",
                        f.get("name").replace("\"", "\\\""), f.get("x"), f.get("y"), f.get("z"), f.get("moving"), f.get("dist")));
                if (i < friends.size() - 1) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }

        private String blocksToJson(List<Map<String, String>> blocks) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < blocks.size(); i++) {
                Map<String, String> b = blocks.get(i);
                sb.append(String.format("{\"x\":\"%s\",\"y\":\"%s\",\"z\":\"%s\",\"name\":\"%s\",\"solid\":\"%s\"}",
                        b.get("x"), b.get("y"), b.get("z"), b.get("name"), b.get("solid")));
                if (i < blocks.size() - 1) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }

        private String listToJson(List<String> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append("\"").append(list.get(i).replace("\"", "\\\"")).append("\"");
                if (i < list.size() - 1) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }

        private String itemsToJson(List<Map<String, String>> items) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                Map<String, String> it = items.get(i);
                sb.append(String.format(Locale.ROOT,
                        "{\"name\":\"%s\",\"x\":%s,\"y\":%s,\"z\":%s}",
                        it.get("name").replace("\"", "\\\""), it.get("x"), it.get("y"), it.get("z")));
                if (i < items.size() - 1) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    static class ActionHandler implements HttpHandler {
        private static int actionCount = 0;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // O Python envia JSON via json.dumps() → "key": valor (com ESPAÇO após
                // os dois-pontos). Os parsers pulam whitespace antes de ler o valor.
                MinecraftForFlies.targetForward = parseJsonFloat(body, "forward", MinecraftForFlies.targetForward);
                MinecraftForFlies.targetStrafing = parseJsonFloat(body, "strafe", MinecraftForFlies.targetStrafing);
                MinecraftForFlies.targetJumping = parseJsonBool(body, "jump", MinecraftForFlies.targetJumping);
                MinecraftForFlies.targetAttacking = parseJsonBool(body, "attack", false);
                MinecraftForFlies.targetYawDelta = parseJsonFloat(body, "yaw_delta", 0f);
                MinecraftForFlies.targetPitchDelta = parseJsonFloat(body, "pitch_delta", 0f);
                String thoughts = parseJsonString(body, "thoughts");
                if (thoughts != null) {
                    MinecraftForFlies.lastThoughts = thoughts;
                }
                // Heartbeat: registra cada /action para o watchdog de morte cerebral
                // no servidor saber que o cérebro está vivo.
                MinecraftForFlies.lastActionAt = System.currentTimeMillis();
                if (++actionCount % 20 == 0) {
                    System.out.println("[DrosophilaBrain] /action aplicado: fwd=" + MinecraftForFlies.targetForward
                            + " strafe=" + MinecraftForFlies.targetStrafing + " yaw_delta=" + MinecraftForFlies.targetYawDelta);
                }
            }
            String response = "{\"status\":\"ok\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }

        private static float parseJsonFloat(String json, String key, float defaultVal) {
            try {
                String search = "\"" + key + "\":";
                int idx = json.indexOf(search);
                if (idx == -1) return defaultVal;
                int start = skipWs(json, idx + search.length());
                int end = start;
                while (end < json.length()) {
                    char c = json.charAt(end);
                    if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                        end++;
                    } else {
                        break;
                    }
                }
                if (start == end) return defaultVal;
                return Float.parseFloat(json.substring(start, end));
            } catch (Exception e) {
                return defaultVal;
            }
        }

        private static boolean parseJsonBool(String json, String key, boolean defaultVal) {
            try {
                String search = "\"" + key + "\":";
                int idx = json.indexOf(search);
                if (idx == -1) return defaultVal;
                int start = skipWs(json, idx + search.length());
                if (json.startsWith("true", start)) return true;
                if (json.startsWith("false", start)) return false;
            } catch (Exception e) {}
            return defaultVal;
        }

        private static String parseJsonString(String json, String key) {
            try {
                String search = "\"" + key + "\"";
                int idx = json.indexOf(search);
                if (idx == -1) return null;
                int colon = json.indexOf(':', idx);
                if (colon == -1) return null;
                int start = skipWs(json, colon + 1);
                if (start >= json.length() || json.charAt(start) != '"') return null;
                int end = start + 1;
                while (end < json.length() && json.charAt(end) != '"') {
                    if (json.charAt(end) == '\\') end++; // pula escape \" etc.
                    end++;
                }
                if (end >= json.length()) return null;
                return json.substring(start + 1, end);
            } catch (Exception e) {
                return null;
            }
        }

        private static int skipWs(String json, int start) {
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
                start++;
            }
            return start;
        }
    }
}