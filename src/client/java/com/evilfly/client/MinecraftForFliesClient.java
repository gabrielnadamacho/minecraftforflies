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

public class MinecraftForFliesClient implements ClientModInitializer {
    private HttpServer server;

    @Override
    public void onInitializeClient() {
        // Renderer da Drosophila (precisa vir antes do servidor HTTP para visual)
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(
                FlyRenderer.LAYER, FlyRenderer::createBodyLayer);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                MinecraftForFlies.DROSOPHILA, FlyRenderer::new);

        System.out.println("[DrosophilaBrain] Inicializando interface biológica e servidor HTTP (porta 8080)...");
        try {
            server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/state", new StateHandler());
            server.createContext("/action", new ActionHandler());
            server.setExecutor(null);
            server.start();
            System.out.println("[DrosophilaBrain] Servidor HTTP pronto em http://localhost:8080");
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
            Minecraft client = Minecraft.getInstance();
            DrosophilaEntity fly = activeFly(client);
            String json = "{}";
            if (fly != null) {
                double x = fly.getX();
                double y = fly.getY();
                double z = fly.getZ();
                float health = fly.getHealth();
                boolean hurt = fly.hurtTime > 0;
                boolean flightAttempt = MinecraftForFlies.targetJumping && !fly.onGround();

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
                List<String> friends = new ArrayList<>();
                for (Entity e : client.level.getEntitiesOfClass(Entity.class,
                        fly.getBoundingBox().inflate(12.0), entity -> entity != fly)) {
                    if (e instanceof Monster) {
                        threats.add(e.getName().getString());
                    } else if (e instanceof Player) {
                        friends.add(e.getName().getString());
                    }
                }

                json = String.format(Locale.ROOT,
                        "{\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"health\":%.1f,\"hurt\":%b,\"flight_attempt\":%b,\"blocks\":%s,\"threats\":%s,\"friends\":%s,\"thoughts\":\"%s\"}",
                        x, y, z, health, hurt, flightAttempt,
                        blocksToJson(blockSensoryData), listToJson(threats), listToJson(friends),
                        MinecraftForFlies.lastThoughts.replace("\"", "\\\"").replace("\n", " "));
            }

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
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
    }

    static class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                MinecraftForFlies.targetForward = parseJsonFloat(body, "forward", MinecraftForFlies.targetForward);
                MinecraftForFlies.targetStrafing = parseJsonFloat(body, "strafe", MinecraftForFlies.targetStrafing);
                MinecraftForFlies.targetJumping = parseJsonBool(body, "jump", MinecraftForFlies.targetJumping);
                MinecraftForFlies.targetAttacking = parseJsonBool(body, "attack", false);
                MinecraftForFlies.targetYawDelta = parseJsonFloat(body, "yaw_delta", 0f);
                MinecraftForFlies.targetPitchDelta = parseJsonFloat(body, "pitch_delta", 0f);
                if (body.contains("\"thoughts\"")) {
                    int idx = body.indexOf("\"thoughts\":\"");
                    if (idx != -1) {
                        int start = idx + 12;
                        int end = body.indexOf("\"", start);
                        if (end != -1) {
                            MinecraftForFlies.lastThoughts = body.substring(start, end);
                        }
                    }
                }
            }
            String response = "{\"status\":\"ok\"}";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }

        private float parseJsonFloat(String json, String key, float defaultVal) {
            try {
                String search = "\"" + key + "\":";
                int idx = json.indexOf(search);
                if (idx == -1) return defaultVal;
                int start = idx + search.length();
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

        private boolean parseJsonBool(String json, String key, boolean defaultVal) {
            try {
                String search = "\"" + key + "\":";
                int idx = json.indexOf(search);
                if (idx == -1) return defaultVal;
                int start = idx + search.length();
                if (json.startsWith("true", start)) return true;
                if (json.startsWith("false", start)) return false;
            } catch (Exception e) {}
            return defaultVal;
        }
    }
}