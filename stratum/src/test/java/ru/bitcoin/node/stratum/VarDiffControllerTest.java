package ru.bitcoin.node.stratum;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.stratum.share.*;
import java.math.BigDecimal;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class VarDiffControllerTest {
    private static final long SECOND = 1_000_000_000L;

    private VarDiffController controller() {
        return new VarDiffController(new VarDiffConfig(true, BigDecimal.ONE, new BigDecimal("256"),
                Duration.ofSeconds(15), Duration.ofSeconds(60)), new BigDecimal("16"));
    }

    @Test void raisesAndLowersDifficultyWithBoundsAndNoWallClockDependency() {
        var controller = controller();
        assertFalse(controller.update(-100 * SECOND));
        for (int i = 0; i < 100; i++) controller.accepted();
        assertFalse(controller.update(-41 * SECOND));
        assertTrue(controller.update(-40 * SECOND));
        assertEquals(0, new BigDecimal("64").compareTo(controller.difficulty()));
        for (int i = 0; i < 100; i++) controller.accepted();
        assertTrue(controller.update(20 * SECOND));
        assertEquals(0, new BigDecimal("256").compareTo(controller.difficulty()));
        for (int i = 0; i < 100; i++) controller.accepted();
        assertFalse(controller.update(80 * SECOND));
        for (int i = 1; i <= 4; i++) assertTrue(controller.update((80 + i * 60L) * SECOND));
        assertEquals(0, BigDecimal.ONE.compareTo(controller.difficulty()));
        assertFalse(controller.update(380 * SECOND));
    }

    @Test void stableRateHasDeadBandAndDisabledModeNeverChanges() {
        var controller = controller();
        controller.update(0);
        for (int i = 0; i < 3; i++) controller.accepted();
        assertFalse(controller.update(60 * SECOND));
        for (int i = 0; i < 5; i++) controller.accepted();
        assertFalse(controller.update(120 * SECOND));
        assertTrue(controller.update(180 * SECOND));
        assertEquals(0, new BigDecimal("4").compareTo(controller.difficulty()));
        var disabled = new VarDiffController(VarDiffConfig.disabled(), new BigDecimal("1e-10"));
        disabled.update(0);
        for (int i = 0; i < 100; i++) disabled.accepted();
        assertFalse(disabled.update(1000 * SECOND));
        assertEquals(new BigDecimal("1e-10"), disabled.difficulty());
    }

    @Test void rejectsInvalidConfigurationAndHandlesSmallRegtestDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> new VarDiffConfig(true, BigDecimal.TEN, BigDecimal.ONE,
                Duration.ofSeconds(15), Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new VarDiffConfig(true, BigDecimal.ONE, BigDecimal.TEN,
                Duration.ZERO, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new VarDiffConfig(true, BigDecimal.ONE, BigDecimal.TEN,
                Duration.ofSeconds(15), Duration.ofSeconds(121)));
        var config = new VarDiffConfig(true, new BigDecimal("1e-12"), BigDecimal.ONE, Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> new VarDiffController(config, BigDecimal.TEN));
        var controller = new VarDiffController(config, new BigDecimal("8e-10"));
        controller.update(0);
        assertTrue(controller.update(2 * SECOND));
        assertEquals(0, new BigDecimal("2e-10").compareTo(controller.difficulty()));
        new ShareValidator(controller.difficulty());
    }
}
