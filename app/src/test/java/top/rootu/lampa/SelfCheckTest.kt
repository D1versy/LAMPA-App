package top.rootu.lampa

import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Канарейка гейта: при D1V_SELFCHECK=1 падает намеренно.
 *
 * Доказывает, что сьюта способна покраснеть. Молча «зелёный» прогон, который на самом деле
 * не запустился (не тот вариант сборки, потерянные ассерты, отвалившийся тулчейн),
 * хуже отсутствия тестов — он создаёт ложную уверенность.
 *
 * Такая же канарейка есть в каждой ноге `scripts\test-all.ps1`.
 */
class SelfCheckTest {

    @Test
    fun `сьюта умеет краснеть`() {
        assertNotEquals(
            "канарейка -SelfCheck: JVM-юниты Android умеют краснеть",
            "1",
            System.getenv("D1V_SELFCHECK")
        )
    }
}
