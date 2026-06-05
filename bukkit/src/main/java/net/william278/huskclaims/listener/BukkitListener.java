/*
 * This file is part of HuskClaims, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.huskclaims.listener;

import lombok.Getter;
import net.william278.cloplib.listener.BukkitOperationListener;
import net.william278.cloplib.operation.Operation;
import net.william278.cloplib.operation.OperationPosition;
import net.william278.cloplib.operation.OperationType;
import net.william278.cloplib.operation.OperationUser;
import net.william278.huskclaims.BukkitHuskClaims;
import net.william278.huskclaims.moderation.SignListener;
import net.william278.huskclaims.position.World;
import net.william278.huskclaims.user.User;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;

@Getter
public class BukkitListener extends BukkitOperationListener implements BukkitPetListener, BukkitDropsListener,
        ClaimsListener, UserListener, SignListener {

    private static final String ENEMY_CLASS_NAME = "org.bukkit.entity.Enemy";
    private static final String KNOCKBACK_EVENT_CLASS_NAME = "org.bukkit.event.entity.EntityKnockbackByEntityEvent";
    private static final String MODERN_END_CRYSTAL_TYPE_NAME = "END_CRYSTAL";
    private static final String LEGACY_END_CRYSTAL_TYPE_NAME = "ENDER_CRYSTAL";
    @Nullable
    private static final EntityType END_CRYSTAL_TYPE = resolveOptionalEntityType(
            MODERN_END_CRYSTAL_TYPE_NAME, LEGACY_END_CRYSTAL_TYPE_NAME
    );

    protected final BukkitHuskClaims plugin;
    @Nullable
    private final Class<?> enemyClass = resolveOptionalClass(ENEMY_CLASS_NAME);

    public BukkitListener(@NotNull BukkitHuskClaims plugin) {
        super(plugin, plugin);
        this.plugin = plugin;
    }

    @Override
    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerArmorStandKnockbackListener();
        setInspectorCallbacks();
    }

    @EventHandler
    public void onPlayerJoin(@NotNull PlayerJoinEvent e) {
        this.onUserJoin(plugin.getOnlineUser(e.getPlayer()));
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent e) {
        this.onUserQuit(plugin.getOnlineUser(e.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerSwitchHeldItem(@NotNull PlayerItemHeldEvent e) {
        final ItemStack mainHand = e.getPlayer().getInventory().getItem(e.getNewSlot());
        final ItemStack offHand = e.getPlayer().getInventory().getItemInOffHand();
        this.onUserSwitchHeldItem(
                plugin.getOnlineUser(e.getPlayer()),
                (mainHand != null ? mainHand.getType() : Material.AIR).getKey().toString(),
                offHand.getType().getKey().toString()
        );
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onUserSwapHands(@NotNull PlayerSwapHandItemsEvent e) {
        final ItemStack mainHand = e.getMainHandItem();
        final ItemStack offHand = e.getOffHandItem();
        this.onUserSwitchHeldItem(
                plugin.getOnlineUser(e.getPlayer()),
                (mainHand != null ? mainHand.getType() : Material.AIR).getKey().toString(),
                (offHand != null ? offHand.getType() : Material.AIR).getKey().toString()
        );
    }

    @EventHandler(ignoreCancelled = true)
    public void onUserTeleport(@NotNull PlayerTeleportEvent e) {
        if (e.getTo() != null && getPlugin().cancelMovement(
                plugin.getOnlineUser(e.getPlayer()),
                BukkitHuskClaims.Adapter.adapt(e.getFrom()),
                BukkitHuskClaims.Adapter.adapt(e.getTo())
        )) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWorldLoad(@NotNull WorldLoadEvent e) {
        plugin.runAsync(() -> {
            final World world = BukkitHuskClaims.Adapter.adapt(e.getWorld());
            plugin.loadClaimWorld(world);
            plugin.getClaimWorld(world).ifPresent(loaded -> plugin.getMapHooks().forEach(
                    hook -> hook.markClaims(loaded.getClaims(), loaded))
            );
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEndCrystalExplode(@NotNull EntityExplodeEvent e) {
        if (!isEndCrystalType(e.getEntityType())) {
            return;
        }

        e.blockList().removeIf(block -> plugin.cancelOperation(Operation.of(
                OperationType.EXPLOSION_DAMAGE_TERRAIN,
                getPosition(block.getLocation())
        )));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockedCrossClaimExplosionDamage(@NotNull EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }

        if (isBlockedCrossClaimExplosion(e.getDamager(), e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockedShulkerBulletDamage(@NotNull EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof ShulkerBullet bullet) || !(bullet.getShooter() instanceof Shulker shooter)) {
            return;
        }

        if (!isHostileMob(e.getEntity()) && isBlockedHostileProjectileDamage(shooter, e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerArmorStandKnockbackListener() {
        final Class<?> optionalEventClass = resolveOptionalClass(KNOCKBACK_EVENT_CLASS_NAME);
        if (optionalEventClass == null || !Event.class.isAssignableFrom(optionalEventClass)) {
            return;
        }

        try {
            final Class<? extends Event> eventClass = (Class<? extends Event>) optionalEventClass;
            final Method getEntityMethod = eventClass.getMethod("getEntity");
            final Method getSourceEntityMethod = eventClass.getMethod("getSourceEntity");
            final EventExecutor executor = (listener, event) -> {
                try {
                    onBlockedArmorStandKnockback(event, getEntityMethod, getSourceEntityMethod);
                } catch (ReflectiveOperationException e) {
                    plugin.log(Level.WARNING, "Failed to process armor stand knockback protection event", e);
                }
            };
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.HIGHEST, executor, plugin, true
            );
        } catch (ReflectiveOperationException e) {
            plugin.log(Level.WARNING, "Failed to register armor stand knockback protection listener", e);
        }
    }

    private void onBlockedArmorStandKnockback(@NotNull Event event, @NotNull Method getEntityMethod,
                                              @NotNull Method getSourceEntityMethod) throws ReflectiveOperationException {
        final Object entity = getEntityMethod.invoke(event);
        if (!(entity instanceof ArmorStand armorStand)) {
            return;
        }

        final Object sourceEntity = getSourceEntityMethod.invoke(event);
        if (!(sourceEntity instanceof Entity source)) {
            return;
        }

        final Optional<Player> player = getPlayerSource(source);
        if (player.isPresent() && isBlockedArmorStandAttack(player.get(), armorStand)
                && event instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
        }
    }

    private boolean isBlockedArmorStandAttack(@NotNull Player player, @NotNull ArmorStand armorStand) {
        return plugin.cancelOperation(Operation.of(
                getUser(player),
                OperationType.PLAYER_DAMAGE_PERSISTENT_ENTITY,
                getPosition(armorStand.getLocation())
        ));
    }

    private boolean isBlockedCrossClaimExplosion(@NotNull Entity source, @NotNull Entity target) {
        final OperationPosition sourcePosition = getPosition(source.getLocation());
        final OperationPosition targetPosition = getPosition(target.getLocation());
        return plugin.cancelNature(targetPosition.getWorld(), sourcePosition, targetPosition);
    }

    private boolean isBlockedHostileProjectileDamage(@NotNull Entity source, @NotNull Entity target) {
        final OperationPosition targetPosition = getPosition(target.getLocation());
        return plugin.cancelOperation(Operation.of(OperationType.MONSTER_DAMAGE_TERRAIN, targetPosition))
                || plugin.cancelNature(targetPosition.getWorld(), getPosition(source.getLocation()), targetPosition);
    }

    private boolean isHostileMob(@NotNull Entity entity) {
        if (enemyClass != null && enemyClass.isInstance(entity)) {
            return true;
        }
        return entity instanceof Monster
                || entity.getType() == EntityType.SHULKER
                || entity.getType() == EntityType.SLIME
                || entity.getType() == EntityType.MAGMA_CUBE
                || entity.getType() == EntityType.PHANTOM
                || entity.getType() == EntityType.GHAST;
    }

    static boolean isEndCrystalType(@NotNull EntityType entityType) {
        return END_CRYSTAL_TYPE != null && entityType == END_CRYSTAL_TYPE;
    }

    @Nullable
    static EntityType resolveOptionalEntityType(@NotNull String... names) {
        for (String name : names) {
            try {
                return EntityType.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Keep trying fallbacks until a matching enum constant is found
            }
        }
        return null;
    }

    @Nullable
    private static Class<?> resolveOptionalClass(@NotNull String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    @Override
    public void onUserTamedEntityAction(@NotNull Cancellable event, @Nullable Entity player, @NotNull Entity entity) {
        // If pets are enabled, check if the entity is tamed
        if (player == null || !getPlugin().getSettings().getPets().isEnabled() || !(entity instanceof Tameable tamed)) {
            return;
        }

        // Check it was damaged by a player
        final Optional<Player> source = getPlayerSource(player);
        final Optional<User> owner = getPlugin().getPetOwner(tamed);
        if (source.isEmpty() || owner.isEmpty()) {
            return;
        }

        // Don't cancel the event if there's no mismatch
        if (getPlugin().cancelPetOperation(plugin.getOnlineUser(source.get()), owner.get())) {
            event.setCancelled(true);
        }
    }

    @Override
    @NotNull
    public OperationPosition getPosition(@NotNull Location location) {
        return BukkitHuskClaims.Adapter.adapt(location);
    }

    @Override
    @NotNull
    public OperationUser getUser(@NotNull Player player) {
        return plugin.getOnlineUser(player);
    }

    @Override
    public void setInspectionDistance(int i) {
        throw new UnsupportedOperationException("Cannot change inspection distance");
    }

}
