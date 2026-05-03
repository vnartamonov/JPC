package org.jpc.emulator.motherboard;

import org.jpc.j2se.Option;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that -no-mp and -no-acpi CLI switches are parsed correctly.
 * The effect on PC component wiring is verified at integration level (boot test).
 */
class NoMpNoAcpiOptionTest {

    @AfterEach
    void resetOptions() {
        Option.parse(new String[0]);
    }

    @Test
    void noMpNotSetByDefault() {
        Option.parse(new String[0]);
        assertFalse(Option.no_mp.isSet(), "-no-mp should be off by default");
    }

    @Test
    void noAcpiNotSetByDefault() {
        Option.parse(new String[0]);
        assertFalse(Option.no_acpi.isSet(), "-no-acpi should be off by default");
    }

    @Test
    void noMpParsedFromCommandLine() {
        Option.parse(new String[]{"-no-mp"});
        assertTrue(Option.no_mp.isSet(), "-no-mp should be set after parsing");
    }

    @Test
    void noAcpiParsedFromCommandLine() {
        Option.parse(new String[]{"-no-acpi"});
        assertTrue(Option.no_acpi.isSet(), "-no-acpi should be set after parsing");
    }

    @Test
    void bothFlagsCanBeSetTogether() {
        Option.parse(new String[]{"-no-mp", "-no-acpi"});
        assertTrue(Option.no_mp.isSet());
        assertTrue(Option.no_acpi.isSet());
    }

    @Test
    void otherOptionsUnaffectedByNoMp() {
        Option.parse(new String[]{"-no-mp", "-ram", "64"});
        assertTrue(Option.no_mp.isSet());
        assertEquals("64", Option.ram.value());
        assertFalse(Option.no_acpi.isSet());
    }
}
