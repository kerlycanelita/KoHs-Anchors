package dev.zymekoh.kohsanchors.compat;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * The additive light material the anchor glow is drawn with: coloured triangles that add light,
 * tested against depth so walls hide them, and writing no depth so lights add up.
 *
 * <p>Up to 26.1.x Vanilla's dragon-ray material is exactly that. 26.2 made the dragon rays write
 * depth, so each lit pixel would hide the light drawn behind it; this copies the pipeline from
 * Vanilla's own with only the depth writes turned off. 26.3 and the older eras have their own copy
 * of this class.</p>
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
            if (!source.getShaderDefines().isEmpty()) {
                throw new IllegalStateException("the dragon-ray pipeline now carries shader defines");
            }
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "pipeline/anchor_glow"))
                    .withVertexShader(source.getVertexShader())
                    .withFragmentShader(source.getFragmentShader())
                    .withPolygonMode(source.getPolygonMode())
                    .withCull(source.isCull())
                    .withColorTargetState(source.getColorTargetState())
                    .withVertexBinding(0, source.getVertexFormatBinding(0))
                    .withPrimitiveTopology(source.getPrimitiveTopology())
                    .withDepthStencilState(new DepthStencilState(source.getDepthStencilState().depthTest(), false));
            for (BindGroupLayout layout : source.getBindGroupLayouts()) {
                builder.withBindGroupLayout(layout);
            }
            RenderSetup setup = RenderSetup.builder(builder.build()).createRenderSetup();
            // RenderType's factory is package-private; the class is what every renderer passes around.
            Method factory = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            factory.setAccessible(true);
            return (RenderType) factory.invoke(null, KoHsAnchorsClient.MOD_ID + "_glow", setup);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not build the anchor glow material; using the dragon rays", exception);
            return RenderTypes.dragonRays();
        }
    }
}
