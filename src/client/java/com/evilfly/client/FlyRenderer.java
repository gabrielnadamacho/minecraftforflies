package com.evilfly.client;

import com.evilfly.MinecraftForFlies;
import com.evilfly.fly.DrosophilaEntity;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renderer da Drosophila como um player normal (Steve) todo preto (#000000),
 * sem asas, sem layers extras (armadura, capa, etc.).
 * Dimensões 0.6x1.8 idênticas a um player vanilla.
 */
public class FlyRenderer extends MobRenderer<DrosophilaEntity, PlayerModel<DrosophilaEntity>> {
    public FlyRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,
                new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), // false = model normal (não slim/Alex)
                0.5F); // shadow size (diametro /2)
    }

    @Override
    public ResourceLocation getTextureLocation(DrosophilaEntity entity) {
        return MinecraftForFlies.id("textures/entity/drosophila.png");
    }
}