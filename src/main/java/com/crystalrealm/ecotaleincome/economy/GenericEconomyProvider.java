package com.crystalrealm.ecotaleincome.economy;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Universal economy adapter that connects to ANY economy plugin via reflection.
 *
 * <p>No coding required — the server owner simply specifies class name and
 * method names in the config file. EcoTaleIncome will find the class and
 * call its methods at runtime.</p>
 *
 * <h3>How it works</h3>
 * <p>The adapter uses Java reflection to locate the economy plugin's API class
 * and invoke its deposit/balance methods. Supports two common API patterns:</p>
 * <ul>
 *   <li><b>Static methods:</b> {@code EconomyAPI.deposit(UUID, double)} — most common</li>
 *   <li><b>Instance via static getter:</b> {@code EconomyAPI.getInstance().deposit(UUID, double)}</li>
 * </ul>
 *
 * <h3>Config example</h3>
 * <pre>{@code
 * "EconomyProvider": "generic",
 * "GenericEconomy": {
 *   "ClassName": "com.example.economy.EconomyAPI",
 *   "InstanceMethod": "",
 *   "DepositMethod": "deposit",
 *   "BalanceMethod": "getBalance",
 *   "DepositHasReason": true
 * }
 * }</pre>
 *
 * @author CrystalRealm
 * @since 1.0.0
 */
public class GenericEconomyProvider implements EconomyProvider {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final String className;
    private final String instanceMethodName;
    private final String depositMethodName;
    private final String balanceMethodName;
    private final boolean depositHasReason;

    // Resolved at runtime via reflection
    private boolean available;
    private Class<?> apiClass;
    private Method instanceMethod;  // null if static API
    private Method depositMethod;
    private Method balanceMethod;

    /**
     * Creates a generic economy provider.
     *
     * @param className        fully qualified class name of the economy API
     * @param instanceMethod   static method that returns the API instance (empty = static API)
     * @param depositMethod    method name for depositing currency
     * @param balanceMethod    method name for getting player balance
     * @param depositHasReason whether the deposit method accepts a reason string as 3rd parameter
     */
    public GenericEconomyProvider(@Nonnull String className,
                                  @Nonnull String instanceMethod,
                                  @Nonnull String depositMethod,
                                  @Nonnull String balanceMethod,
                                  boolean depositHasReason) {
        this.className = className;
        this.instanceMethodName = instanceMethod;
        this.depositMethodName = depositMethod;
        this.balanceMethodName = balanceMethod;
        this.depositHasReason = depositHasReason;

        resolve();
    }

    /**
     * Resolves the economy API class and methods via reflection.
     */
    private void resolve() {
        try {
            // 1. Find the API class
            apiClass = Class.forName(className);
            LOGGER.info("GenericEconomy: Found class {}", className);

            // 2. Find instance method (if specified)
            Object targetForLookup = null;
            Class<?> targetClass = apiClass;

            if (instanceMethodName != null && !instanceMethodName.isEmpty()) {
                instanceMethod = apiClass.getMethod(instanceMethodName);
                // The instance method returns an object — we need its class for method lookup
                targetClass = instanceMethod.getReturnType();
                LOGGER.info("GenericEconomy: Instance method {}.{}() → {}",
                        className, instanceMethodName, targetClass.getName());
            }

            // 3. Find deposit method
            depositMethod = findDepositMethod(targetClass);
            if (depositMethod == null) {
                LOGGER.error("GenericEconomy: Could not find deposit method '{}' in {}",
                        depositMethodName, targetClass.getName());
                available = false;
                return;
            }
            LOGGER.info("GenericEconomy: Deposit method: {}", depositMethod);

            // 4. Find balance method
            balanceMethod = findBalanceMethod(targetClass);
            if (balanceMethod == null) {
                LOGGER.error("GenericEconomy: Could not find balance method '{}' in {}",
                        balanceMethodName, targetClass.getName());
                available = false;
                return;
            }
            LOGGER.info("GenericEconomy: Balance method: {}", balanceMethod);

            available = true;
            LOGGER.info("GenericEconomy: Successfully resolved all methods for '{}'.", className);

        } catch (ClassNotFoundException e) {
            LOGGER.warn("GenericEconomy: Class '{}' not found. Is the economy plugin installed?", className);
            available = false;
        } catch (Exception e) {
            LOGGER.error("GenericEconomy: Failed to resolve API: {}", e.getMessage());
            available = false;
        }
    }

    /**
     * Tries multiple deposit method signatures in order of likelihood.
     */
    private Method findDepositMethod(Class<?> target) {
        // Try with reason parameter first (UUID, double, String)
        if (depositHasReason) {
            try {
                return target.getMethod(depositMethodName, UUID.class, double.class, String.class);
            } catch (NoSuchMethodException ignored) {}
        }

        // (UUID, double)
        try {
            return target.getMethod(depositMethodName, UUID.class, double.class);
        } catch (NoSuchMethodException ignored) {}

        // (String, double) — some plugins use player name
        try {
            return target.getMethod(depositMethodName, String.class, double.class);
        } catch (NoSuchMethodException ignored) {}

        // (UUID, double, String) — try even if not configured
        if (!depositHasReason) {
            try {
                return target.getMethod(depositMethodName, UUID.class, double.class, String.class);
            } catch (NoSuchMethodException ignored) {}
        }

        // (UUID, int) — some use integer amounts
        try {
            return target.getMethod(depositMethodName, UUID.class, int.class);
        } catch (NoSuchMethodException ignored) {}

        return null;
    }

    /**
     * Tries multiple balance method signatures.
     */
    private Method findBalanceMethod(Class<?> target) {
        // (UUID) → double
        try {
            return target.getMethod(balanceMethodName, UUID.class);
        } catch (NoSuchMethodException ignored) {}

        // (String) → double — player name variant
        try {
            return target.getMethod(balanceMethodName, String.class);
        } catch (NoSuchMethodException ignored) {}

        // No-arg (unlikely but possible for instance-based APIs with context)
        try {
            return target.getMethod(balanceMethodName);
        } catch (NoSuchMethodException ignored) {}

        return null;
    }

    /**
     * Gets the API instance (for instance-based APIs) or null (for static APIs).
     */
    private Object getApiInstance() throws Exception {
        if (instanceMethod == null) return null;
        return instanceMethod.invoke(null);
    }

    // ═════════════════════════════════════════════════════════════
    //  EconomyProvider implementation
    // ═════════════════════════════════════════════════════════════

    @Nonnull
    @Override
    public String getName() {
        return "Generic (" + className + ")";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean deposit(@Nonnull UUID playerUuid, double amount, @Nonnull String reason) {
        if (!available || depositMethod == null) return false;
        try {
            Object target = getApiInstance();
            Class<?>[] paramTypes = depositMethod.getParameterTypes();
            Object result;

            if (paramTypes.length == 3 && paramTypes[2] == String.class) {
                // (UUID, double, String)
                result = depositMethod.invoke(target, playerUuid, amount, reason);
            } else if (paramTypes.length == 2 && paramTypes[0] == UUID.class && paramTypes[1] == double.class) {
                // (UUID, double)
                result = depositMethod.invoke(target, playerUuid, amount);
            } else if (paramTypes.length == 2 && paramTypes[0] == String.class) {
                // (String, double)
                result = depositMethod.invoke(target, playerUuid.toString(), amount);
            } else if (paramTypes.length == 2 && paramTypes[0] == UUID.class && paramTypes[1] == int.class) {
                // (UUID, int)
                result = depositMethod.invoke(target, playerUuid, (int) amount);
            } else {
                LOGGER.warn("GenericEconomy: Unsupported deposit method signature.");
                return false;
            }

            // Handle return type: boolean or void
            if (result instanceof Boolean) return (Boolean) result;
            return true; // void methods assumed success

        } catch (Exception e) {
            LOGGER.warn("GenericEconomy: Deposit failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public double getBalance(@Nonnull UUID playerUuid) {
        if (!available || balanceMethod == null) return -1;
        try {
            Object target = getApiInstance();
            Class<?>[] paramTypes = balanceMethod.getParameterTypes();
            Object result;

            if (paramTypes.length == 1 && paramTypes[0] == UUID.class) {
                result = balanceMethod.invoke(target, playerUuid);
            } else if (paramTypes.length == 1 && paramTypes[0] == String.class) {
                result = balanceMethod.invoke(target, playerUuid.toString());
            } else if (paramTypes.length == 0) {
                result = balanceMethod.invoke(target);
            } else {
                LOGGER.warn("GenericEconomy: Unsupported balance method signature.");
                return -1;
            }

            // Handle return type: double, int, long, float, Number
            if (result instanceof Number) return ((Number) result).doubleValue();
            return -1;

        } catch (Exception e) {
            LOGGER.warn("GenericEconomy: GetBalance failed: {}", e.getMessage());
            return -1;
        }
    }
}
