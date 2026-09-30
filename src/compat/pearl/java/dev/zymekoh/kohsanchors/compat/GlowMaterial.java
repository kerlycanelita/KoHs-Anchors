package dev.zymekoh.kohsanchors.compat;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * 26.3: the dragon rays still write depth, the pipeline API lives in {@code com.mojang.renderpearl},
 * and Vanilla keeps an order-independent-transparency variant for players who enable it.
 */
public final class GlowMaterial {
    private static RenderType glow;

    private GlowMaterial() {
    }

    public static RenderType glow() {
        if (glow == null) {
            glow = create();
        }
        return glow;
    }

    private static RenderType create() {
        RenderPipeline source = RenderPipelines.DRAGON_RAYS;
        try {
            if (!source.getShaderDefines().isEmpty() || source.pushConstantSize() != 0) {
                throw new IllegalStateException("the dragon-ray pipeline changed shape");
            }
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "pipeline/anchor_glow"))
                    .withVertexShader(source.getShaders().get(ShaderType.VERTEX))
                    .withFragmentShader(source.getShaders().get(ShaderType.FRAGMENT))
                    .withPolygonMode(source.getPolygonMode())
                    .withCull(source.isCull())
                    .withVertexBinding(0, source.getVertexFormatBinding(0))
                    .withPrimitiveTopology(source.getPrimitiveTopology())
                    .withDepthStencilState(new DepthStencilState(source.getDepthStencilState().depthTest(), false));
            List<ColorTargetState> targets = source.getColorTargetStates();
            for (int index = 0; index < targets.size(); index++) {
                builder.withColorTargetState(index, targets.get(index));
            }
            for (BindGroupLayout layout : source.getBindGroupLayouts()) {
                builder.withBindGroupLayout(layout);
            }
            RenderSetup setup = RenderSetup.builder(builder.build())
                    .setOitPipelines(RenderPipelines.OIT_DRAGON_RAYS)
                    .createRenderSetup();
            Method factory = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            factory.setAccessible(true);
            return (RenderType) factory.invoke(null, KoHsAnchorsClient.MOD_ID + "_glow", setup);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not build the anchor glow material; using the dragon rays", exception);
            return RenderTypes.dragonRays();
        }
    }
}
