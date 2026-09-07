package com.evilfly.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MinecraftForFliesClient implements ClientModInitializer {
    private static HttpServer server;
    public static volatile float targetForward = 0f;
    public static volatile float targetStrafing = 0f;
    public static volatile boolean targetJumping = false;
    public static volatile boolean targetAttacking = false;
    public static volatile float targetYawDelta = 0f;
    public static volatile float targetPitchDelta = 0f;
    public static volatile String lastThoughts = "Drosophila melanogaster ativa no substrato.";

    private static int flightAttemptCounter = 0;
    private static boolean flightAttemptDetected = false;

    @Override
    public void onInitializeClient() {
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null) return;

            // Apply walking movement (Drosophila walks on legs, does not fly)
            player.input.movementForward = targetForward;
            player.input.movementSideways = targetStrafing;
            player.input.jumping = false; // Prevent natural jumping / flying

            // Detect flight / take-off attempts (when jump is requested while airborne or attempting lift-off)
            if (targetJumping && !player.isOnGround()) {
                flightAttemptCounter++;
                if (flightAttemptCounter > 10) {
                    flightAttemptDetected = true; // Signals converter.py to inject neural noise punishment
                    flightAttemptCounter = 0;
                }
            } else {
                flightAttemptDetected = false;
                if (targetJumping) {
                    // Small ground hop allowed, but sustained lift-off is flagged
                    flightAttemptCounter++;
                    if (flightAttemptCounter > 15) {
                        flightAttemptDetected = true;
                    }
                } else {
                    flightAttemptCounter = Math.max(0, flightAttemptCounter - 1);
                }
            }

            if (targetYawDelta != 0f) {
                player.setYaw(player.getYaw() + targetYawDelta);
                targetYawDelta = 0f;
            }
            if (targetPitchDelta != 0f) {
                float newPitch = Math.max(-90f, Math.min(90f, player.getPitch() + targetPitchDelta));
                player.setPitch(newPitch);
                targetPitchDelta = 0f;
            }
        });
    }

    static class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            MinecraftClient client = MinecraftClient.getInstance();
            String json = "{}";
            if (client.player != null && client.world != null) {
                ClientPlayerEntity p = client.player;
                double x = p.getX();
                double y = p.getY();
                double z = p.getZ();
                float health = p.getHealth();
                boolean hurt = p.hurtTime > 0;

                // Direct data teaching the fly what blocks are what (3x3x3 local sensory grid)
                List<Map<String, String>> blockSensoryData = new ArrayList<>();
                BlockPos pos = p.getBlockPos();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos bp = pos.add(dx, dy, dz);
                            BlockState state = client.world.getBlockState(bp);
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

                // Entity recognition: Hostile mobs = threats, Real players = friendly
                List<String> threats = new ArrayList<>();
                List<String> friends = new ArrayList<>();
                for (Entity e : client.world.getEntitiesByClass(Entity.class, p.getBoundingBox().expand(12.0), entity -> entity != p)) {
                    if (e instanceof HostileEntity) {
                        threats.add(e.getName().getString());
                    } else if (e instanceof PlayerEntity) {
                        friends.add(e.getName().getString());
                    }
                }

                json = String.format(Locale.ROOT,
                    "{\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"health\":%.1f,\"hurt\":%b,\"flight_attempt\":%b,\"blocks\":%s,\"threats\":%s,\"friends\":%s,\"thoughts\":\"%s\"}",
                    x, y, z, health, hurt, flightAttemptDetected,
                    blocksToJson(blockSensoryData), listToJson(threats), listToJson(friends),
                    lastThoughts.replace("\"", "\\\"").replace("\n", " ")
                );
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
                targetForward = parseJsonFloat(body, "forward", targetForward);
                targetStrafing = parseJsonFloat(body, "strafe", targetStrafing);
                targetJumping = parseJsonBool(body, "jump", targetJumping);
                targetAttacking = parseJsonBool(body, "attack", false);
                targetYawDelta = parseJsonFloat(body, "yaw_delta", 0f);
                targetPitchDelta = parseJsonFloat(body, "pitch_delta", 0f);
                if (body.contains("\"thoughts\"")) {
                    int idx = body.indexOf("\"thoughts\":\"");
                    if (idx != -1) {
                        int start = idx + 12;
                        int end = body.indexOf("\"", start);
                        if (end != -1) {
                            lastThoughts = body.substring(start, end);
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
