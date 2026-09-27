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
