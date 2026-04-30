# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

JPC is a pure-Java x86 PC hardware emulator (boots DOS, Windows 9x, some Linuxes) with a Swing-based debugger that supports breakpoints, watchpoints, and time-travel.

## Build & Run

Maven multi-module, Java 17 (compiles with `--release 17`; runs on any JDK ≥ 17).

- `mvn -DskipTests package` — build all modules. All build output lives in a single top-level `target/` directory: `target/jpc-app/JPCApplication.jar` (the runnable uber-jar) plus `target/{jpc-core,jpc-debugger,jpc-tools}/<artifact>.jar`.
- `mvn -pl jpc-app -am -DskipTests package` — build only the runnable uber-jar (and its dependencies).
- `mvn -pl jpc-core -am compile` — fast compile-only check of the emulator core.
- `mvn clean` — wipe `target/`.

Run the emulator: `java -jar target/jpc-app/JPCApplication.jar -boot hda -hda yourdiskimage.img` (or `-help`). The shaded jar's manifest includes `Default-Args: -fda mem:resources/images/floppy.img -hda mem:resources/images/dosgames.img -boot fda`, so a bare `java -jar` boots the bundled FreeDOS floppy.

A `dir:` prefix on a disk arg mounts a host directory as a virtual FAT32 drive; add `dir:sync:` to write through to the host files.

## Module layout

| Maven module    | Java packages                                                     | Notes                                       |
|-----------------|-------------------------------------------------------------------|---------------------------------------------|
| `jpc-core`      | `org.jpc.emulator.*`, `org.jpc.support`, `org.jpc.j2se`           | Emulator + host I/O + Swing front-end       |
| `jpc-debugger`  | `org.jpc.debugger`                                                | Depends on core                             |
| `jpc-tools`     | `tools` (default package)                                         | Depends on core; `Main-Class: tools.Tools`  |
| `jpc-app`       | (no own sources)                                                  | Uber-jar `JPCApplication.jar` via shade     |

Resources live in `jpc-core/src/main/resources/resources/{bios,images,...}` — the inner `resources/` directory is intentional: code references files as `resources/images/floppy.img` and the `mem:resources/...` strings in the default-args read those paths from the classpath.

## Regenerating the decoder

`jpc-core/src/main/java/org/jpc/emulator/execution/decoder/ExecutableTables.java` and the per-opcode handler classes under `jpc-core/src/main/java/org/jpc/emulator/execution/opcodes/{rm,pm,vm}/` are **generated**, not hand-written, from the XML opcode tables in `jpc-tools/src/main/resources/Opcodes_*.xml`. The XML tables and the `tools.Tools -decoder` entry point are inputs; checked-in `.java` files are outputs.

Use `./regenerate_decoder.sh` after changing the XMLs or the generator. The script:

1. Builds `jpc-tools` and its core dependency.
2. Runs `tools.Tools -decoder`, redirecting stdout into the `ExecutableTables.java` file in `jpc-core`.
3. Rebuilds `jpc-app` so the regenerated decoder ships in the runnable jar.

`tools.Opcode` resolves the output directory from the `jpc.opcodes.dir` system property, defaulting to `jpc-core/src/main/java/org/jpc/emulator/execution/opcodes` (relative to the project root). Override it with `-Djpc.opcodes.dir=...` if running the generator from a different working directory.

## Instruction tests & fuzzing

- `tests/<mnemonic>/` directories hold per-instruction regression fixtures. They're not JUnit; they're inputs for `tools.TestGenerator` / `tools.OracleFuzzer`.
- `targetedFuzz.sh <config>` discovers the unique instruction encodings used while booting a target config, sorts rarest-first, and feeds them to `tools.Tools -testgen` for randomized testing.
- `tools.CompareToBochs` / `tools.OracleFuzzer` cross-check JPC's CPU against Bochs as an oracle.

## Architecture

The emulator is a software CPU + motherboard model, with hardware components implementing `HardwareComponent` and assembled into a `PC` (`jpc-core/src/main/java/org/jpc/emulator/PC.java`).

Top-level Java packages:

- `org.jpc.emulator` — the `PC` aggregate plus the timer/clock and `HardwareComponent` infrastructure.
- `org.jpc.emulator.processor` — `Processor`, segment models (real/protected/V86, expand-down), descriptor tables, task switching, exceptions, FPU (`fpu64/`).
- `org.jpc.emulator.memory` — physical/linear address spaces, paging via `TLB` (fast/slow variants), EPROM, alignment-checked space, `LazyCodeBlockMemory` (the bridge between memory writes and code-block invalidation), `LinearAddressTranslator` (page-walk helper used by both PC and the debugger).
- `org.jpc.emulator.motherboard` — chipset bits: PIC, PIT (Bochs port), DMA, RTC, IO-port routing, BIOS/VGABIOS shadow ROMs, A20 gate.
- `org.jpc.emulator.peripheral` — floppy, keyboard, serial, PC speaker, SoundBlaster/Adlib/MPU401 audio, mixer.
- `org.jpc.emulator.pci` (+ `pci.peripheral`) — PCI bus host bridge, IDE, VGA card, ethernet card.
- `org.jpc.emulator.execution` — the heart of the JIT-ish executor:
  - `decoder/` — x86 instruction decoder. `ExecutableTables.java` is generated; `Disassembler`, `BasicBlock`, `Instruction`, `Modrm`, `Prefices`, `OpcodeDecoder` implement the decode pipeline.
  - `opcodes/{rm,pm,vm}/` — generated per-opcode `Executable` subclasses, one per (mnemonic, operand-encoding, mode) tuple. The mode subdirectory matters: real-mode, protected-mode, and virtual-8086 implementations are separate classes even for the "same" instruction.
  - `codeblock/` — `CodeBlock` cache and compiler stack: `CodeBlockManager` owns the cache; `Interpreted{Real,ProtectedMode,VM86Mode}Block` are baseline interpreters; `OptimisedCompiler` + `BackgroundCompiler` produce optimized blocks; `Spanning*CodeBlock` handle blocks that cross page boundaries.
- `org.jpc.support` — host-side I/O glue: `BlockDevice`/`SeekableIODevice` hierarchy (file-backed, array-backed, caching, remote-RMI variants), `TreeBlockDevice` (the `dir:`/`dir:sync:` FAT32 mount), `DriveSet`, `EthernetHub`, command-line `ArgProcessor`.
- `org.jpc.j2se` — Swing front-end, applet wrapper, `JPCApplication` (main class), `PCMonitor` (screen), `KeyMapping`, `VirtualClock`, and `Option` (the central command-line option registry — read this when adding a new flag).
- `org.jpc.debugger` — Swing debugger UI (frames for breakpoints, watchpoints, memory, processor state, execution trace, opcode/codeblock frequency). `ProcessorAccess` (and `ReflectionProcessorAccess`, `TimeTravelProcessorAccess`) are the abstraction the UI uses to read/write CPU state.
- `tools.*` — standalone build-time/dev tools (decoder generator, test generator, Bochs comparator, fuzzer). Lives in `jpc-tools`, not shipped inside `JPCApplication.jar`.

### Cross-cutting things to know

- **CPU mode matters everywhere.** Real / protected / virtual-8086 each have their own segment classes, their own code-block classes, and their own opcode-handler subdirectory. When fixing a bug in one mode, check whether the same fix is needed in the others.
- **Self-modifying code** invalidates cached code blocks via `LazyCodeBlockMemory` and `SelfModifyingCodeException`. Memory writes that touch a region with cached blocks must go through this path.
- **Determinism mode** (`-deterministic`, `-start-time`) is load-bearing for the test/fuzz infrastructure — don't introduce non-deterministic time/randomness on the emulation path without gating it.
- **Adding a CLI option** means adding a field to `org.jpc.j2se.Option` (in `jpc-core`) and (usually) a help line in `Option.printHelp()`. Other code reads the option via the static field.
- **Default-Args lives in the shade plugin.** The `Default-Args` manifest entry of the runnable jar is configured in `jpc-app/pom.xml` under the maven-shade-plugin's `ManifestResourceTransformer`. Changing default boot behavior of the shipped jar means editing that POM, not the Makefile (which no longer exists).
- **Module dependency direction is core ← debugger / tools / app.** The emulator core must not import `org.jpc.debugger`. The page-walk helper `LinearAddressTranslator` was extracted into `emulator.memory` precisely to avoid that edge — keep it there if you touch this code.
