package com.crystalrealm.ecotaleincome.reward;

/**
 * Результат расчёта награды.
 *
 * @param amount     финальная сумма после всех множителей
 * @param baseAmount базовая сумма до множителей
 * @param source     описание источника ("Zombie", "Gold Ore", "Wheat")
 * @param category   категория ("mob", "ore", "wood", "crop")
 */
public record RewardResult(
        double amount,
        double baseAmount,
        String source,
        String category
) {
    /**
     * @return true если награда положительная
     */
    public boolean isValid() {
        return amount > 0.0;
    }

    /**
     * @return форматированная сумма (2 знака после запятой)
     */
    public String formattedAmount() {
        return String.format("%.2f", amount);
    }
}
