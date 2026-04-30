# RESUME — JPC × NetBSD/i386 boot

Этот файл — точка передачи работы другому агенту. Он читает его → может продолжить с того же места без перечитывания всей истории.

---

## 1. Контекст проекта

- **Репозиторий:** `/Users/vnrtmnv/Programming/JCP` — JPC, чистоJava x86 PC-эмулятор (Maven multi-module, Java 17). Цель: загрузить **NetBSD/i386 11.0_RC2** через установочный ISO, минимум — до `sysinst`.
- **Архитектура JPC:** software-CPU + motherboard + paging. Modes: real / protected / virtual-8086. Long mode/SSE/SSE2 ОТСУТСТВУЮТ. Уровни CPU: 486/Pentium/PII (default — 6 после инкремента 0002).
- **Ведение работы:** инкременты в `.report/0001.md` … `.report/0008.md`, индекс — `.report/MAIN.md`. Перед каждым инкрементом — план; после — apply + JUnit + `mvn package` зелёные. Все предложения по улучшению — в § «Предложения по улучшению» каждого отчёта.
- **Project guide:** `CLAUDE.md` в корне.

## 2. Что уже сделано (инкременты)

| # | Сабэтап | Что | Файлы |
|---|---------|-----|-------|
| 0001 | анализ | gap-анализ NetBSD 11.0 vs JPC; Этап B как roadmap | `.report/0001.md` |
| 0002 | B1+B2  | default cpuLevel 5→6 (PII); seedDefaultMSRs в Processor (`IA32_APIC_BASE=0xFEE00900`, `IA32_PAT`, `IA32_MTRRCAP`, `IA32_MISC_ENABLE`) | `Processor.java`, `Option.java` |
| 0003 | B4     | Static MP 1.4 table overlay в shadow ROM на `0xFC000` | `motherboard/MpTable.java` (новый) |
| 0004 | B3     | LAPIC stub @0xFEE00000 + IO-APIC stub @0xFEC00000 (только storage, прерывания не доставляются) | `motherboard/LocalApic.java`, `motherboard/IoApic.java` (новые) |
| 0005 | B5     | ACPI 1.0 (RSDP+RSDT+MADT+FACP+DSDT) overlay на `0xFB000` | `motherboard/Acpi.java` (новый) |
| 0006 | B6     | `IdeStats` per-IDEChannel + `-trace-ide` flag | `pci/peripheral/IdeStats.java` (новый), правки `IDEChannel.java`, `Option.java` |
| 0007 | B7     | Первая попытка boot ISO. Результат: kernel загрузился полностью до device probe, упал в `spllower+0x37` (с `boot-com.iso`) | `.report/0007.md` |
| 0008 | B7-followup | Диагностические инструменты: `FaultLogger` + `-log-fault`; `EipPoller` + `-eip-poll <ms>` (с детектором STALL по `vmClock.getTicks()`) | `processor/FaultLogger.java`, `processor/EipPoller.java` (новые) |

**Тесты:** 114 JUnit-тестов в `jpc-core/src/test/java/`, все зелёные. Выполнить: `mvn -pl jpc-core test`.
**Сборка:** `mvn -DskipTests package` ⇒ `target/jpc-app/JPCApplication.jar` (~22 MB).

⚠️ **Осторожно с инкрементальной сборкой Maven:** shade-plugin не всегда пересобирает jar. Если нужно убедиться что изменения в jar — делать `rm -rf target && mvn -DskipTests -q package`.

## 3. Текущая точка работы

**Активный инкремент: 0008.** Документ `.report/0008.md` содержит план; реализация: 60 % (диагностические инструменты + локализация падения сделаны, фикс не написан).

### 3.1. Что делал последним

Запустил `JPCApplication` с `-log-fault -eip-poll 5000` на boot-com.iso, поймал hang. По `EipPoller` STALLED-меткам видно:
- Ticks замораживаются на `11271247404` (поток PC Execute умер).
- EIP застывает на `cs:eip=0008:C01279B1` (kernel-text).

После добавления `-XX:-OmitStackTraceInFastThrow` в JVM-аргументы и оборачивания `printInsHistory()` в try/catch (чтобы не маскировал оригинальный exception) **получил полный stack trace**:

```
java.lang.NullPointerException: Cannot invoke
    "org.jpc.emulator.memory.Memory.getDoubleWord(int)" because "m" is null
    at org.jpc.emulator.memory.LinearAddressSpace.getDoubleWord(LinearAddressSpace.java:592)
    at org.jpc.emulator.processor.Segment.getDoubleWord(Segment.java:102)
    at org.jpc.emulator.execution.decoder.Pointer.get32(Pointer.java:183)
    at org.jpc.emulator.execution.opcodes.pm.mov_Gd_Ed_mem.execute(mov_Gd_Ed_mem.java:52)
    at .codeblock.InterpretedProtectedModeBlock.execute
    at .codeblock.AbstractCodeBlockWrapper.execute
    at .codeblock.BackgroundCompiler$ExecuteCountingCodeBlockWrapper.execute
    at .memory.LazyCodeBlockMemory.executeProtected(LazyCodeBlockMemory.java:89)
    at .memory.LinearAddressSpace.executeProtected(LinearAddressSpace.java:706)
    at .emulator.PC.executeProtected(PC.java:1110)
    at .emulator.PC.execute(PC.java:979)
    at .j2se.PCMonitorFrame.run(PCMonitorFrame.java:260)
```

### 3.2. Что это значит

Кернел NetBSD выполняет `mov reg32, [mem]` на адресе `C01279B1`. JPC при чтении из linear address заходит в `LinearAddressSpace.getDoubleWord(LinearAddressSpace.java:592)`:

```java
public int getDoubleWord(int offset) {
    try {
        try {
            return getReadMemoryBlockAt(offset).getDoubleWord(offset & BLOCK_MASK);
        }
        catch (ArrayIndexOutOfBoundsException e) { return super.getDoubleWord(offset); }
    }
    catch (NullPointerException e) {}
    catch (ProcessorException p) {}

    Memory m = validateTLBEntryRead(offset);
    try {
        return m.getDoubleWord(offset & BLOCK_MASK);   // ← NPE here, m == null
    }
    ...
}
```

`validateTLBEntryRead(offset)` вернул `null`. Это путь либо через `pagingDisabled` (TLB cache miss), либо через 4K-page (`tlb.getReadMemoryBlockAt(...)` line 345). Архитектурный лог:

- `validateTLBEntryRead` (line 244) **никогда не должен возвращать null** — всегда либо реальная Memory, либо `PF_NOT_PRESENT_RS/RU/...` PageFaultWrapper, либо `UnconnectedMemoryBlock`.
- Тем не менее — null. Скорее всего race в TLB (set + get не атомарно), либо `tlb.getReadMemoryBlockAt` имеет недокументированный nullable путь.

### 3.3. Дополнительный контекст: hang vs crash

Пользователь явно подчеркнул:
- Система **не падает, а наглухо зависает** (`он не падает, а нагрухо зависает`).
- Признак hang — **неподвижный счётчик `vmClock.getTicks()`** (`то что он завис можно узнать по неподвижному счетчику тактовой частоты`). EipPoller теперь это и детектит — выводит метку `STALLED`.

Hang происходит ПОТОМУ ЧТО Java executor thread `PC Execute` умер с NPE; Swing-окно остаётся живым, но эмуляция остановилась → ticks замораживаются. До добавления stack-trace это выглядело как «висит на eip C01279B1» — но реально eip просто не двигается, потому что НИКТО не выполняет инструкции.

В прошлом инкременте 0007 на похожем месте был «fatal privileged instruction fault» от NetBSD — это другая ситуация (там JPC поднял exception, NetBSD ddb принял его). Сейчас (после моих правок 0002-0006 и более длинных прогонов) он не доходит до того trap'а.

## 4. Конкретно что делать дальше

### 4.1. Immediate (приоритет P0)

1. **Найти точное место в `validateTLBEntryRead`, где возвращается null.** Возможные пути (в `LinearAddressSpace.java`):
   - line 248-249 (paging-disabled): `tlb.setReadMemoryBlockAt(...); return tlb.getReadMemoryBlockAt(...);` — может ли set+get вернуть null?
   - line 343-345 (4K page): тот же паттерн — `tlb.setReadMemoryBlockAt(isSupervisor, offset, target.getReadMemoryBlockAt(fourKStartAddress)); ... return tlb.getReadMemoryBlockAt(isSupervisor, offset);` — если `target.getReadMemoryBlockAt(fourKStartAddress)` вернул UNCONNECTED, тогда tlb должна вернуть UNCONNECTED, но если null — bug.
   - line 284-298 (4M page): аналогично.

2. **Проверить TLB.getReadMemoryBlockAt контракт.** Найти класс TLB (см. `org.jpc.emulator.memory.TLB`) и убедиться что `getReadMemoryBlockAt(boolean, int)` возвращает то что было записано через `setReadMemoryBlockAt`. Если есть decay/eviction между set и get — баг.

3. **Hot-fix:** в `getDoubleWord` (и аналогичных `getByte`/`getWord`/`getQuadWord`) добавить null-check перед `m.getDoubleWord(...)`. Если `m == null` — это эквивалентно «страница отсутствует» → бросить `ProcessorException(PAGE_FAULT, ...)` (правильный код 0/4/2/6 в зависимости от R/W и user/supervisor). Это **не корректное исправление** (надо разобраться, почему `validateTLBEntryRead` вернул null), но даст guest корректный сигнал и не зависнет.

4. **После фикса** — повторить boot, посмотреть что дальше:
   ```bash
   rm -rf target && mvn -DskipTests -q package
   java -XX:-OmitStackTraceInFastThrow -jar target/jpc-app/JPCApplication.jar \
        -boot cdrom -cdrom infrastructure/boot-com.iso -ram 64 -log-fault -eip-poll 5000 \
        > /tmp/jpc.stdout 2> /tmp/jpc.stderr &
   sleep 130 && kill -9 $!
   grep -A40 "exception stack trace" /tmp/jpc.stdout
   grep -E "STALLED|fault dispatch" /tmp/jpc.stderr | tail -20
   grep -E "INFO:.*\[ " /tmp/jpc.stderr | sed 's/.*INFO: //' | grep -v "^[|/-]*$" | tail -10
   ```

### 4.2. Параллельно (P1, не блокеры этой итерации)

- ACPI/MP overlay'ы (0003, 0005) и Bochs BIOS dynamic tables (0xfb778-0xfcc00) **конфликтуют** — Bochs пишет свои таблицы поверх наших EPROM (writes теряются), NetBSD затем не находит RSDP. Решение из 0007 §3.1: **убрать наши overlay'ы, довериться Bochs BIOS**. Сделать через CLI-флаг `-no-mp`/`-no-acpi` — отключает добавление компонента в `PC.parts`.
- `cpuLevel == 6` дефолт, но NetBSD читает CPUID и видит `Intel 586-class, id 0x513` (Pentium). В каком-то пути CPUID вернул family=5. Дотрейсить через `Processor.cpuid()`.
- `-trace-ide` (0006) не выводит ни одного `ATA cmd` / `ATAPI cmd` несмотря на активность. Логгер isolated, не пропагирует до root. Возможно нужно `Logger.getLogger("").setLevel(Level.INFO)` или прямой `System.err.println`.
- `-no-screen` flag декларирован (`Option.noScreen`), но `JPCApplication.main()` всегда создаёт `JFrame` → headless mode (`-Djava.awt.headless=true`) падает с `HeadlessException`. Простой fix: проверять `Option.noScreen.isSet()` перед `setVisible(true)`.

## 5. Инфраструктура для тестов (важно — не качать заново)

Папка `infrastructure/` (создана пользователем):

- `infrastructure/NetBSD-11.0_RC2-i386.iso` (~710 MB) — full install ISO, **VGA-only bootloader**. С `-cdrom` без `-no-screen`-фикса не виден serial output (загрузчик пишет в VGA). На нём дальше всего удалось дойти до `cs:eip=C01279B1`, но кернел не выводится.
- `infrastructure/boot-com.iso` (~250 MB) — minimal boot CD, **serial bootloader (COM1)**. Удобен для диагностики; кернел печатает банер и device probe в serial. **Использовать его для всех runs**.

Команда для воспроизведения hang:
```bash
java -XX:-OmitStackTraceInFastThrow -jar target/jpc-app/JPCApplication.jar \
     -boot cdrom -cdrom infrastructure/boot-com.iso -ram 64 \
     -log-fault -eip-poll 5000 \
     > /tmp/jpc.stdout 2> /tmp/jpc.stderr &
JPC_PID=$!
sleep 130
kill -9 $JPC_PID
```

Hang проявится через ~105 секунд real time с ticksDelta=0 и неподвижным `cs:eip=0008:C01279B1`.

## 6. Добавленные диагностические инструменты

### 6.1. `-log-fault`

Каждый dispatch protected/v8086/realmode-исключения через `Processor.handleProtectedModeException` / `handleVirtual8086ModeException` / `handleRealModeException` дампит:

- exception type/vector/error code
- saved EIP (как linear) + 16 байт инструкции + dis­assembly
- все 8 GPR
- все 6 selectors
- EFLAGS, CR0, CR2, CR3, CR4, CPL, V86 mode
- 8 dwords стека

Реализация: `org.jpc.emulator.processor.FaultLogger`, hooks в `Processor.handleProtectedModeException` (line ~4163), `handleVirtual8086ModeException` (line ~4584), `handleRealModeException` (line ~4114).

**Caveat:** Если NPE происходит ВНЕ exception dispatch (как в нашем случае: NPE в `mov_Gd_Ed_mem.execute`), `-log-fault` молчит — там нет `ProcessorException`.

### 6.2. `-eip-poll <ms>`

Daemon-thread каждые N мс печатает в лог one-liner: time, mode, cpl, cs:eip, ticks, ticksDelta, GPR, eflags. Между двумя соседними poll'ами с одинаковым ticks → метка `STALLED`.

Реализация: `org.jpc.emulator.processor.EipPoller`, инстанцирован в `JPCApplication.main()` если `Option.eip_poll.value() != null`.

### 6.3. PC.execute() — дамп stack trace при RuntimeException

`PC.execute()` (line 974) теперь:
1. Печатает `--- emulator-side exception stack trace ---` + `e.printStackTrace(System.out)`.
2. `printInsHistory()` обёрнут в try/catch, чтобы CCE не маскировал оригинальный exception.

⚠️ **Обязательно запускать с `-XX:-OmitStackTraceInFastThrow`**, иначе JVM элиминирует stack trace для повторно бросаемых NPE.

## 7. Состав изменений (since 0007)

```
A   .report/0008.md                                                  (план B7-followup)
A   jpc-core/src/main/java/org/jpc/emulator/processor/FaultLogger.java
A   jpc-core/src/main/java/org/jpc/emulator/processor/EipPoller.java
A   jpc-core/src/test/java/org/jpc/emulator/processor/FaultLoggerTest.java
A   jpc-core/src/test/java/org/jpc/emulator/processor/EipPollerTest.java
M   jpc-core/src/main/java/org/jpc/emulator/processor/Processor.java   (+3 hooks)
M   jpc-core/src/main/java/org/jpc/emulator/PC.java                    (catch с printStackTrace)
M   jpc-core/src/main/java/org/jpc/j2se/Option.java                    (log_fault, eip_poll)
M   jpc-core/src/main/java/org/jpc/j2se/JPCApplication.java            (start EipPoller)
A   infrastructure/boot-com.iso                                        (250 MB)
```

`mvn -pl jpc-core test` ⇒ 114 tests, все зелёные (5 EipPollerTest + 7 FaultLoggerTest + предыдущие 102).

Не закоммичено (`git status --short` покажет всё выше как модифицированное/untracked).

## 8. Нерешённые вопросы (для следующего агента)

1. **Почему `validateTLBEntryRead` возвращает null?** Это корневая причина текущего hang. Без понимания этого hot-fix будет only заметанием под ковёр.

2. **NetBSD кернел делает `mov reg32, [mem]` на каком виртуальном адресе?** Расшифровать `mov_Gd_Ed_mem.execute`: какой ModRM, какой регистр source. Поможет идентифицировать какую структуру кернел читает.

3. **Связано ли это с тем, что `validateTLBEntryRead` walks page tables через `target.getDoubleWord(directoryAddress)`** — а target это PhysicalAddressSpace. Если directoryAddress > 64 МБ → UNCONNECTED → `0xFF FF FF FF` → `directoryPresent=true` (bit 0 set) → walk continues с мусорной информацией → `tableAddress` всякий → ... possibly null somehow.

4. **Может быть имеет смысл сначала** убрать ACPI/MP overlay'ы (P1 из 0007) — чтобы NetBSD не пытался использовать LAPIC, и пошёл бы более простым путём. Это может изменить ту же точку crash.

## 9. Полезные команды

```bash
# Полная пересборка (важно — shade-plugin кешируется!)
rm -rf target && mvn -DskipTests -q package

# Тесты
mvn -pl jpc-core test

# Boot test
java -XX:-OmitStackTraceInFastThrow -jar target/jpc-app/JPCApplication.jar \
     -boot cdrom -cdrom infrastructure/boot-com.iso -ram 64 \
     -log-fault -eip-poll 5000 \
     > /tmp/jpc.stdout 2> /tmp/jpc.stderr &
sleep 130 && kill -9 $!

# Проверка bytecode после edit (sanity check что shade jar свежий)
javap -c -cp target/jpc-app/JPCApplication.jar org.jpc.emulator.PC \
   | grep -A 5 "exception stack trace" | head

# Анализ run output
grep -A40 "exception stack trace" /tmp/jpc.stdout
grep -E "STALLED|fault dispatch" /tmp/jpc.stderr
grep -E "INFO:.*\[ " /tmp/jpc.stderr | sed 's/.*INFO: //' | grep -v "^[|/-]*$"
```
