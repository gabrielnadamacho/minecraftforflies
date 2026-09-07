package com.evilfly.client;

import com.evilfly.MinecraftForFlies;
import com.evilfly.fly.DrosophilaEntity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class FlyRenderer extends MobRenderer<DrosophilaEntity, FlyRenderer.FlyModel> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            MinecraftForFlies.id("drosophila"), "main");

    public FlyRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new FlyModel(ctx.bakeLayer(LAYER)), 0.25F);
    }

    @Override
    public ResourceLocation getTextureLocation(DrosophilaEntity entity) {
        return MinecraftForFlies.id("textures/entity/drosophila.png");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // Corpo: pequeno cubo escuro (o "abdômen" da mosca)
        root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, -1.5F, -2.0F, 4.0F, 3.0F, 4.0F),
                PartPose.offset(0.0F, 22.0F, 0.0F));
        // Asas: duas placas finas (height 0) laterais
        root.addOrReplaceChild("wing_l",
                CubeListBuilder.create().texOffs(0, 10).addBox(0.0F, -1.5F, -3.0F, 8.0F, 0.0F, 6.0F),
                PartPose.offset(0.0F, 22.0F, 0.0F));
        root.addOrReplaceChild("wing_r",
                CubeListBuilder.create().texOffs(0, 10).addBox(-8.0F, -1.5F, -3.0F, 8.0F, 0.0F, 6.0F),
                PartPose.offset(0.0F, 22.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 32);
    }

    public static class FlyModel extends EntityModel<DrosophilaEntity> {
        private final ModelPart body;
        private final ModelPart wingL;
        private final ModelPart wingR;

        public FlyModel(ModelPart root) {
            this.body = root.getChild("body");
            this.wingL = root.getChild("wing_l");
            this.wingR = root.getChild("wing_r");
        }

        @Override
        public void setupAnim(DrosophilaEntity entity, float limbSwing, float limbSwingAmount,
                              float ageInTicks, float netHeadYaw, float headPitch) {
            // Bater de asas contínuo + orientar corpo conforme cabeça
            float flap = (float) Math.sin(ageInTicks * 0.9F) * 0.5F;
            this.wingL.zRot = flap;
            this.wingR.zRot = -flap;
            this.body.xRot = headPitch * ((float) Math.PI / 180.0F);
            this.body.yRot = netHeadYaw * ((float) Math.PI / 180.0F);
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                                   int packedOverlay, float red, float green, float blue, float alpha) {
            this.body.render(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
            this.wingL.render(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
            this.wingR.render(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }
}