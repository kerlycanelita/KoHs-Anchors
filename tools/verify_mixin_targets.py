"""Checks every KoHs Anchor's Mixin target against the real Minecraft bytecode of each version.

Compiling proves the Java is valid, not that an injection point exists: a Mixin whose target
moved fails only when the game starts. This reads the Minecraft jars Loom already downloaded,
disassembles the target methods with javap and checks that each target, and the execution order
the code relies on, is there.

    python tools/verify_mixin_targets.py            # every version in gradle/versions.properties
    python tools/verify_mixin_targets.py 26.2 26.3  # just these

Needs each version built once (so Loom has cached its jars) and a JDK 25 javap.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOOM = Path.home() / ".gradle" / "caches" / "fabric-loom" / "minecraftMaven" / "net" / "minecraft"
JAVAP_CANDIDATES = [
    Path(os.environ.get("JAVA_HOME", "")) / "bin" / "javap.exe",
    Path("C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/javap.exe"),
]


def javap_path():
    for candidate in JAVAP_CANDIDATES:
        if candidate.is_file():
            return str(candidate)
    return "javap"


def versions():
    matrix = (ROOT / "gradle" / "versions.properties").read_text(encoding="utf-8")
    return [line.split("=")[0].strip() for line in matrix.splitlines()
            if line.strip() and not line.startswith("#") and "=" in line]


def classpath(version):
    if version.startswith("1."):
        tag = f"{version}-loom.mappings.{version.replace('.', '_')}.layered+hash.2198-v2"
        client = LOOM / "minecraft-clientonly" / tag / f"minecraft-clientonly-{tag}.jar"
        common = LOOM / "minecraft-common" / tag / f"minecraft-common-{tag}.jar"
    else:
        client = LOOM / "minecraft-clientonly-deobf" / version / f"minecraft-clientonly-deobf-{version}.jar"
        common = LOOM / "minecraft-common-deobf" / version / f"minecraft-common-deobf-{version}.jar"
    for jar in (client, common):
        if not jar.is_file():
            raise FileNotFoundError(f"{jar} is missing: build -Pmc={version} once first")
    return f"{client};{common}" if os.name == "nt" else f"{client}:{common}"


def disassemble(version, class_name):
    result = subprocess.run([javap_path(), "-c", "-p", "-cp", classpath(version), class_name],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def method_body(listing, name, descriptor_hint=""):
    """The instructions of the first method called `name` whose header contains the hint."""
    lines = listing.splitlines()
    body = []
    inside = False
    for line in lines:
        if re.match(r"^  \S.*[ .]" + re.escape(name) + r"\(", line) and descriptor_hint in line:
            inside = True
            continue
        if inside:
            if re.match(r"^  \S", line) or line.startswith("}"):
                break
            body.append(line)
    return body if inside else None


def calls(body, pattern):
    return [index for index, line in enumerate(body) if re.search(pattern, line)]


def check_version(version):
    problems = []
    modern = not version.startswith("1.")

    minecraft = disassemble(version, "net.minecraft.client.Minecraft")
    keybinds = method_body(minecraft, "handleKeybinds")
    if keybinds is None:
        problems.append("Minecraft.handleKeybinds() is missing")
    else:
        hotbar = calls(keybinds, r"getfield .*Options\.keyHotbarSlots:\[Lnet/minecraft/client/KeyMapping;")
        uses = calls(keybinds, r"invoke\w+ .*Method startUseItem:\(\)V")
        use_clicks = calls(keybinds, r"getfield .*Options\.keyUse:")
        consume = calls(keybinds, r"KeyMapping\.consumeClick:\(\)Z")
        if len(hotbar) != 1:
            problems.append(f"expected 1 read of keyHotbarSlots in handleKeybinds, found {len(hotbar)}")
        if len(uses) < 2:
            problems.append(f"expected 2 startUseItem calls in handleKeybinds, found {len(uses)}")
        elif hotbar and hotbar[0] > uses[0]:
            problems.append("the hotbar keys are no longer applied before the use presses")
        else:
            # Ordinal 0 must be the call of the use-press loop: keyUse.consumeClick() right before.
            before = [line for line in keybinds[max(0, uses[0] - 8):uses[0]]]
            if not any("keyUse" in line for line in before) or not any("consumeClick" in line for line in before):
                problems.append("startUseItem ordinal 0 is not the use-press loop")
            repeat = keybinds[max(0, uses[1] - 12):uses[1]]
            if not any("rightClickDelay" in line for line in repeat):
                problems.append("startUseItem ordinal 1 is not the held-key repeat")
        if not use_clicks or not consume:
            problems.append("handleKeybinds no longer consumes keyUse clicks")
    if method_body(minecraft, "startUseItem", "()") is None and " startUseItem()" not in minecraft:
        problems.append("Minecraft.startUseItem() is missing")
    if modern and not re.search(r"private void pick\(float\);", minecraft):
        problems.append("Minecraft.pick(float) is missing")
    if not modern:
        renderer = disassemble(version, "net.minecraft.client.renderer.GameRenderer")
        if not re.search(r"public void pick\(float\);", renderer):
            problems.append("GameRenderer.pick(float) is missing or not public")

    key_mapping = disassemble(version, "net.minecraft.client.KeyMapping")
    if not re.search(r"public static void click\(com\.mojang\.blaze3d\.platform\.InputConstants\$Key\);", key_mapping):
        problems.append("KeyMapping.click(Key) is missing")
    if not re.search(r"protected com\.mojang\.blaze3d\.platform\.InputConstants\$Key key;", key_mapping):
        problems.append("KeyMapping.key field is missing")
    if not re.search(r"private int clickCount;", key_mapping):
        problems.append("KeyMapping.clickCount field is missing")

    anchor = disassemble(version, "net.minecraft.world.level.block.RespawnAnchorBlock")
    if not re.search(r"protected net\.minecraft\.world\.InteractionResult useWithoutItem\(net\.minecraft\.world\.level\.block"
                     r"\.state\.BlockState, net\.minecraft\.world\.level\.Level, net\.minecraft\.core\.BlockPos, "
                     r"net\.minecraft\.world\.entity\.player\.Player, net\.minecraft\.world\.phys\.BlockHitResult\);",
                     anchor):
        problems.append("RespawnAnchorBlock.useWithoutItem signature changed")

    listener = disassemble(version, "net.minecraft.client.multiplayer.ClientPacketListener")
    explosion = method_body(listener, "handleExplosion")
    if explosion is None:
        problems.append("ClientPacketListener.handleExplosion is missing")
    else:
        sound = calls(explosion, r"ClientLevel\.playLocalSound:\(DDDLnet/minecraft/sounds/SoundEvent;"
                                 r"Lnet/minecraft/sounds/SoundSource;FFZ\)V")
        flash = calls(explosion, r"ClientLevel\.addParticle:\(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD\)V")
        debris = calls(explosion, r"ClientLevel\.trackExplosionEffects:\(Lnet/minecraft/world/phys/Vec3;FI"
                                  r"Lnet/minecraft/util/random/WeightedList;\)V")
        if len(sound) != 1 or len(flash) != 1 or len(debris) != 1:
            problems.append(f"handleExplosion calls: sound {len(sound)}, flash {len(flash)}, debris {len(debris)}")
        elif not sound[0] < flash[0] < debris[0]:
            # DetonationPredictor decides on the sound and reuses the decision for the next two.
            problems.append("handleExplosion no longer plays sound, then flash, then debris")

    # The early-click hold is released when the server's state for the anchor arrives, which
    # ClientLevelServerStateMixin sees at the head of setServerVerifiedBlockState.
    level = disassemble(version, "net.minecraft.client.multiplayer.ClientLevel")
    if not re.search(r"public void setServerVerifiedBlockState\(net\.minecraft\.core\.BlockPos, "
                     r"net\.minecraft\.world\.level\.block\.state\.BlockState, int\);", level):
        problems.append("ClientLevel.setServerVerifiedBlockState(BlockPos, BlockState, int) is missing")
    reporters = []
    current = None
    for line in listener.splitlines():
        if re.match(r"^  \S", line):
            current = line
        elif "ClientLevel.setServerVerifiedBlockState:" in line and current is not None:
            reporters.append(current)
    # handleBlockUpdate, plus the section-update lambda (lambda$handleChunkBlocksUpdate$0, or
    # method_34007 in the remapped 1.21.11 jar).
    if not any(" handleBlockUpdate(" in line for line in reporters) or len(reporters) < 2:
        problems.append("block and section updates no longer arrive through setServerVerifiedBlockState")

    # The veil: the chunk mesher's block reads, the section re-mesh it asks for, the crosshair
    # outline it hides and the client tick that expires it (see AnchorVeil).
    region = disassemble(version, "net.minecraft.client.renderer.chunk.RenderSectionRegion")
    if not re.search(r"public net\.minecraft\.world\.level\.block\.state\.BlockState getBlockState\("
                     r"net\.minecraft\.core\.BlockPos\);", region):
        problems.append("RenderSectionRegion.getBlockState(BlockPos) is missing")
    if not re.search(r"public void setSectionDirtyWithNeighbors\(int, int, int\);", level):
        problems.append("ClientLevel.setSectionDirtyWithNeighbors(int, int, int) is missing")
    state_class = ("net.minecraft.client.renderer.state.LevelRenderState" if not modern
                   else "net.minecraft.client.renderer.state.level.LevelRenderState")
    outline_owner = ("net.minecraft.client.renderer.extract.LevelExtractor"
                     if version not in ("1.21.11", "26.1", "26.1.1", "26.1.2")
                     else "net.minecraft.client.renderer.LevelRenderer")
    outline = disassemble(version, outline_owner)
    if not re.search(r"private void extractBlockOutline\(net\.minecraft\.client\.Camera, "
                     + re.escape(state_class) + r"\);", outline):
        problems.append(f"{outline_owner}.extractBlockOutline(Camera, LevelRenderState) is missing")
    render_state = disassemble(version, state_class)
    if not re.search(r"public [\w.]+BlockOutlineRenderState blockOutlineRenderState;", render_state):
        problems.append("LevelRenderState.blockOutlineRenderState is missing")
    if method_body(minecraft, "tick", "()") is None:
        problems.append("Minecraft.tick() is missing")

    # 0.4.0: the debounce and own-anchor hook, the detonation acknowledgement, the glow, the skin
    # and the charge sound.
    game_mode = disassemble(version, "net.minecraft.client.multiplayer.MultiPlayerGameMode")
    if not re.search(r"public net\.minecraft\.world\.InteractionResult useItemOn\(net\.minecraft\.client\.player\.LocalPlayer, "
                     r"net\.minecraft\.world\.InteractionHand, net\.minecraft\.world\.phys\.BlockHitResult\);", game_mode):
        problems.append("MultiPlayerGameMode.useItemOn(LocalPlayer, InteractionHand, BlockHitResult) is missing")
    use_item = method_body(minecraft, "startUseItem", "()")
    if use_item is not None and not any("InteractionResult$Fail" in line for line in use_item):
        problems.append("startUseItem no longer stops at a FAIL from useItemOn")
    if not re.search(r"public void handleBlockChangedAck\(int\);", level):
        problems.append("ClientLevel.handleBlockChangedAck(int) is missing")
    if not re.search(r"private final net\.minecraft\.client\.multiplayer\.prediction\.BlockStatePredictionHandler "
                     r"blockStatePredictionHandler;", level):
        problems.append("ClientLevel.blockStatePredictionHandler is missing")
    handler = disassemble(version, "net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler")
    if not re.search(r"public int currentSequence\(\);", handler):
        problems.append("BlockStatePredictionHandler.currentSequence() is missing")
    # A server state for a block the player's own prediction is waiting on is kept aside and
    # applied at the acknowledgement: DetonationPredictor and AnchorVeil read the block at the tail
    # of setServerVerifiedBlockState to tell which, and again at the tail of handleBlockChangedAck.
    verified = method_body(level, "setServerVerifiedBlockState", "(net.minecraft.core.BlockPos")
    if verified is None or not calls(verified, r"BlockStatePredictionHandler\.updateKnownServerState"):
        problems.append("setServerVerifiedBlockState no longer keeps states aside through updateKnownServerState")
    acknowledged = method_body(level, "handleBlockChangedAck", "(int)")
    if acknowledged is None or not calls(acknowledged, r"BlockStatePredictionHandler\.endPredictionsUpTo"):
        problems.append("handleBlockChangedAck no longer applies the kept states through endPredictionsUpTo")

    level_renderer = disassemble(version, "net.minecraft.client.renderer.LevelRenderer")
    storage = "SubmitNodeStorage" if version in ("1.21.11", "26.1", "26.1.1", "26.1.2") else "SubmitNodeCollector"
    if not re.search(r"private void submitBlockEntities\(com\.mojang\.blaze3d\.vertex\.PoseStack, "
                     + re.escape(state_class) + r", net\.minecraft\.client\.renderer\." + storage + r"\);", level_renderer):
        problems.append(f"LevelRenderer.submitBlockEntities(PoseStack, LevelRenderState, {storage}) is missing")
    ordered = disassemble(version, "net.minecraft.client.renderer.OrderedSubmitNodeCollector")
    if "submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.rendertype.RenderType, " \
            "net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer)" not in ordered:
        problems.append("OrderedSubmitNodeCollector.submitCustomGeometry is missing")
    camera_state = "net.minecraft.client.renderer.state.CameraRenderState" if not modern \
        else "net.minecraft.client.renderer.state.level.CameraRenderState"
    if not re.search(r"public " + re.escape(camera_state) + r" cameraRenderState;", render_state):
        problems.append("LevelRenderState.cameraRenderState is missing")
    camera = disassemble(version, camera_state)
    if not re.search(r"public net\.minecraft\.world\.phys\.Vec3 pos;", camera):
        problems.append("CameraRenderState.pos is missing")
    if not re.search(r"public org\.joml\.Quaternionf orientation;", camera):
        problems.append("CameraRenderState.orientation is missing (the glow's view cone)")
    pipelines = disassemble(version, "net.minecraft.client.renderer.RenderPipelines")
    if not re.search(r"public static final [\w.]+RenderPipeline DRAGON_RAYS;", pipelines):
        problems.append("RenderPipelines.DRAGON_RAYS is missing")

    atlas = disassemble(version, "net.minecraft.client.renderer.texture.TextureAtlas")
    if not re.search(r"public void upload\(net\.minecraft\.client\.renderer\.texture\.SpriteLoader\$Preparations\);", atlas):
        problems.append("TextureAtlas.upload(SpriteLoader.Preparations) is missing")
    contents = disassemble(version, "net.minecraft.client.renderer.texture.SpriteContents")
    if not re.search(r"public net\.minecraft\.client\.renderer\.texture\.SpriteContents\$AnimationState "
                     r"createAnimationState\(", contents):
        problems.append("SpriteContents.createAnimationState is missing")
    animation = disassemble(version, "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
    if not re.search(r"it\.unimi\.dsi\.fastutil\.ints\.Int2ObjectMap<[\w.]+GpuTextureView> frameTexturesByIndex;", animation):
        problems.append("SpriteContents.AnimationState.frameTexturesByIndex is missing")
    sprite = disassemble(version, "net.minecraft.client.renderer.texture.TextureAtlasSprite")
    if not re.search(r"private final int padding;", sprite):
        problems.append("TextureAtlasSprite.padding is missing")

    sound = method_body(listener, "handleSoundEvent")
    if sound is None or not calls(sound, r"ClientLevel\.playSeededSound:\(Lnet/minecraft/world/entity/Entity;DDD"
                                         r"Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ\)V"):
        problems.append("handleSoundEvent no longer plays through ClientLevel.playSeededSound(Entity, x, y, z, Holder, ...)")

    # 0.4.0: no light left where the veil hides an anchor, and bounce light dropped on block changes.
    block_light = disassemble(version, "net.minecraft.world.level.lighting.BlockLightEngine")
    emission = method_body(block_light, "getEmission", "(long, net.minecraft.world.level.block.state.BlockState)")
    if emission is None:
        problems.append("BlockLightEngine.getEmission(long, BlockState) is missing")
    elif len(calls(emission, r"BlockState\.getLightEmission:\(\)I")) != 1:
        problems.append("BlockLightEngine.getEmission no longer reads BlockState.getLightEmission() exactly once")
    light_engine = disassemble(version, "net.minecraft.world.level.lighting.LightEngine")
    if not re.search(r"protected final net\.minecraft\.world\.level\.chunk\.LightChunkGetter chunkSource;", light_engine):
        problems.append("LightEngine.chunkSource is missing")
    # 0.5.1: the client's light engines read a veiled block as drawn (LightEngineVeilMixin).
    if not re.search(r"protected net\.minecraft\.world\.level\.block\.state\.BlockState getState\(net\.minecraft\.core\.BlockPos\);",
                     light_engine):
        problems.append("LightEngine.getState(BlockPos) is missing")
    for engine in ("BlockLightEngine", "SkyLightEngine"):
        body = disassemble(version, f"net.minecraft.world.level.lighting.{engine}")
        if not calls(body.splitlines(), r"getState:\(Lnet/minecraft/core/BlockPos;\)Lnet/minecraft/world/level/block/state/BlockState;"):
            problems.append(f"{engine} no longer reads its blocks through LightEngine.getState")
    level_light = disassemble(version, "net.minecraft.world.level.lighting.LevelLightEngine")
    if not re.search(r"public void checkBlock\(net\.minecraft\.core\.BlockPos\);", level_light):
        problems.append("LevelLightEngine.checkBlock(BlockPos) is missing")
    if not re.search(r"public void sendBlockUpdated\(net\.minecraft\.core\.BlockPos, net\.minecraft\.world\.level\.block\.state\."
                     r"BlockState, net\.minecraft\.world\.level\.block\.state\.BlockState, int\);", level):
        problems.append("ClientLevel.sendBlockUpdated(BlockPos, BlockState, BlockState, int) is missing")
    chunk_cache = disassemble(version, "net.minecraft.client.multiplayer.ClientChunkCache")
    if "LevelLightEngine.\"<init>\":(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V" not in chunk_cache:
        problems.append("ClientChunkCache no longer builds its LevelLightEngine on itself")

    # 0.4.0: one slot change per tick, before its clicks (Grim's PacketOrderE / MultiPlace).
    keybinds = method_body(minecraft, "handleKeybinds", "()")
    if keybinds is None or len(calls(keybinds, r"KeyMapping\.consumeClick:\(\)Z")) < 5:
        problems.append("handleKeybinds no longer takes its presses through KeyMapping.consumeClick()")
    tick = method_body(minecraft, "tick", "()")
    if tick is None:
        problems.append("Minecraft.tick() is missing")
    else:
        tick_end = calls(tick, r"ServerboundClientTickEndPacket\.INSTANCE")
        returns = [index for index, line in enumerate(tick) if re.search(r"\breturn\s*$", line)]
        keybind_calls = calls(tick, r"Method handleKeybinds:\(\)V")
        if not tick_end or len(returns) != 1 or tick_end[-1] > returns[0] or not keybind_calls \
                or keybind_calls[0] > tick_end[0]:
            problems.append("Minecraft.tick no longer sends the tick-end packet after handleKeybinds and before its end")
    game_mode_p = disassemble(version, "net.minecraft.client.multiplayer.MultiPlayerGameMode")
    if not re.search(r"private int carriedIndex;", game_mode_p):
        problems.append("MultiPlayerGameMode.carriedIndex is missing")
    if not re.search(r"private void ensureHasSentCarriedItem\(\);", game_mode_p):
        problems.append("MultiPlayerGameMode.ensureHasSentCarriedItem() is missing")
    if not re.search(r"public net\.minecraft\.world\.InteractionResult useItem\(net\.minecraft\.world\.entity\.player\.Player, "
                     r"net\.minecraft\.world\.InteractionHand\);", game_mode_p):
        problems.append("MultiPlayerGameMode.useItem(Player, InteractionHand) is missing")
    if not re.search(r"public void attack\(net\.minecraft\.world\.entity\.player\.Player, net\.minecraft\.world\.entity\.Entity\);",
                     game_mode_p):
        problems.append("MultiPlayerGameMode.attack(Player, Entity) is missing")
    for name in ("useItemOn", "useItem", "attack"):
        body = method_body(game_mode_p, name, "(")
        if body is None or not calls(body, r"Method ensureHasSentCarriedItem:\(\)V"):
            problems.append(f"MultiPlayerGameMode.{name} no longer sends the carried slot first")
    return problems


def main():
    selected = sys.argv[1:] or versions()
    failed = False
    for version in selected:
        try:
            problems = check_version(version)
        except (FileNotFoundError, RuntimeError) as error:
            problems = [str(error)]
        if problems:
            failed = True
            print(f"{version}: FAIL")
            for problem in problems:
                print(f"  - {problem}")
        else:
            print(f"{version}: ok")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
