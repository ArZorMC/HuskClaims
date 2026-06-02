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

import com.google.common.collect.Lists;
import lombok.Getter;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.filter.FilteredMessage;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.Explosion;
import net.william278.cloplib.listener.FabricOperationListener;
import net.william278.cloplib.operation.Operation;
import net.william278.cloplib.operation.OperationPosition;
import net.william278.cloplib.operation.OperationType;
import net.william278.cloplib.operation.OperationUser;
import net.william278.huskclaims.FabricHuskClaims;
import net.william278.huskclaims.moderation.SignListener;
import net.william278.huskclaims.moderation.SignWrite;
import net.william278.huskclaims.position.World;
import net.william278.huskclaims.user.User;
import net.william278.huskclaims.util.Location;
import net.william278.huskclaims.util.PlayerActionEvents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

@Getter
public class FabricListener extends FabricOperationListener implements FabricPetListener, FabricDropsListener,
        ClaimsListener, UserListener, SignListener {

    protected final FabricHuskClaims plugin;

    public FabricListener(@NotNull FabricHuskClaims plugin) {
        super(plugin, plugin.getMod());
        this.plugin = plugin;
    }

    @Override
    public void register() {
        setInspectorCallbacks();

        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(this::onPlayerQuit);
        ServerWorldEvents.LOAD.register(this::onWorldLoad);
        PlayerActionEvents.AFTER_HELD_ITEM_CHANGE.register(this::onUserChangeHeldItems);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onPlayerDamageTamed);
        UseEntityCallback.EVENT.register(this::onPlayerTamedInteract);
        PlayerActionEvents.BEFORE_CHANGE_TEXT_ON_SIGN.register(this::onSignEdit);
        PlayerActionEvents.AFTER_DEATH_DROP_ITEMS.register(this::onPlayerDeath);
    }

    private void onPlayerJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        plugin.getOnlineUserMap().remove(handler.getPlayer().getUuid());
        this.onUserJoin(plugin.getOnlineUser(handler.getPlayer()));
    }


    public void onPlayerQuit(ServerPlayNetworkHandler handler, MinecraftServer server) {
        this.onUserQuit(plugin.getOnlineUser(handler.getPlayer()));
    }

    public void onUserChangeHeldItems(@NotNull ServerPlayerEntity player,
                                      @NotNull ItemStack mainHandItem, @NotNull ItemStack offHandItme) {
        this.onUserSwitchHeldItem(
                plugin.getOnlineUser(player),
                Registries.ITEM.getId(mainHandItem.isEmpty() ? Items.AIR : mainHandItem.getItem()).asString(),
                Registries.ITEM.getId(offHandItme.isEmpty() ? Items.AIR : offHandItme.getItem()).asString()
        );
    }

    public List<FilteredMessage> onSignEdit(SignBlockEntity sign, ServerPlayerEntity player, boolean front, List<FilteredMessage> messages) {
        SignWrite write = handleSignEdit(SignWrite.create(
            plugin.getOnlineUser(player),
            FabricHuskClaims.Adapter.adapt(new Location((ServerWorld) sign.getWorld(), sign.getPos())),
            front ? SignWrite.Type.SIGN_EDIT_FRONT : SignWrite.Type.SIGN_EDIT_BACK,
            messages.stream().map(FilteredMessage::getString).toArray(String[]::new),
            plugin.getServerName()
        ));

        if (write.getFilteredText() == null) {
            return messages;
        }

        List<FilteredMessage> filteredMessages = Lists.newArrayList();
        for (int l = 0; l < messages.size(); l++) {
            filteredMessages.add(FilteredMessage.permitted(write.getFilteredText().get(l)));
        }
        return filteredMessages;
    }

//    @EventHandler(ignoreCancelled = true)
//    public void onUserTeleport(@NotNull PlayerTeleportEvent e) {
//        if (e.getTo() != null && getPlugin().cancelMovement(
//                plugin.getOnlineUser(e.getPlayer()),
//                BukkitHuskClaims.Adapter.adapt(e.getFrom()),
//                BukkitHuskClaims.Adapter.adapt(e.getTo())
//        )) {
//            e.setCancelled(true);
//        }
//    }

    public void onWorldLoad(@NotNull MinecraftServer server, @NotNull ServerWorld serverWorld) {
        plugin.runAsync(() -> {
            final World world = FabricHuskClaims.Adapter.adapt(serverWorld);
            plugin.loadClaimWorld(world);
            plugin.getClaimWorld(world).ifPresent(loaded -> plugin.getMapHooks().forEach(
                    hook -> hook.markClaims(loaded.getClaims(), loaded))
            );
        });
    }

    @Override
    @NotNull
    public ActionResult onExplosionDamageEntity(@NotNull Explosion explosion, @NotNull Entity entity) {
        if (entity == explosion.getCausingEntity()) {
            return ActionResult.PASS;
        }

        if (explosion.getCausingEntity() != null && isBlockedCrossClaimExplosion(explosion.getCausingEntity(), entity)) {
            return ActionResult.FAIL;
        }

        final OperationPosition entityPos = getPosition(entity.getPos(), entity.getWorld(), entity.getYaw(), entity.getPitch());
        if ((entity instanceof AbstractDecorationEntity || entity instanceof ArmorStandEntity) &&
                getHandler().cancelOperation(Operation.of(
                        OperationType.EXPLOSION_DAMAGE_TERRAIN,
                        entityPos
                ))) {
            return ActionResult.FAIL;
        }

        final Optional<ServerPlayerEntity> player = getPlayerSource(explosion.getCausingEntity());
        if (player.isPresent() && !player.get().isSpectator()) {
            return handlePlayerDamageEntity(player.get(), entity);
        }

        if (!isMonster(entity) && getHandler().cancelOperation(Operation.of(
                OperationType.EXPLOSION_DAMAGE_ENTITY,
                entityPos
        ))) {
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    @Override
    @NotNull
    public ActionResult onProjectileHitEntity(@NotNull Entity hit, @NotNull ProjectileEntity projectile,
                                              @Nullable Entity shooter, @Nullable BlockPos dispensedFrom) {
        final Optional<ServerPlayerEntity> playerShooter = getPlayerSource(shooter);
        if (playerShooter.isPresent()) {
            return handlePlayerDamageEntity(playerShooter.get(), hit);
        }

        if (dispensedFrom != null) {
            final OperationPosition dispenserPos = getPosition(dispensedFrom, hit.getWorld());
            return getHandler().cancelNature(
                    dispenserPos.getWorld(),
                    getPosition(hit.getPos(), hit.getWorld(), hit.getYaw(), hit.getPitch()),
                    dispenserPos
            ) ? ActionResult.FAIL : ActionResult.PASS;
        }

        if (projectile instanceof ShulkerBulletEntity && shooter instanceof ShulkerEntity && !isMonster(hit)
                && isBlockedHostileProjectileDamage(shooter, hit)) {
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    @Override
    public boolean onUserTamedEntityAction(@Nullable Entity player, @NotNull Entity entity) {
        if (player == null || !plugin.getSettings().getPets().isEnabled() || !(entity instanceof TameableEntity tamed)) {
            return true;
        }

        final Optional<ServerPlayerEntity> source = getPlayerSource(player);
        final Optional<User> owner = plugin.getPetOwner(tamed);
        if (source.isEmpty() || owner.isEmpty()) {
            return true;
        }

        return !plugin.cancelPetOperation(plugin.getOnlineUser(source.get()), owner.get());
    }

    @NotNull
    private ActionResult handlePlayerDamageEntity(@NotNull ServerPlayerEntity attacker, @NotNull Entity damaged) {
        final OperationPosition damagedPos = getPosition(
                damaged.getPos(), damaged.getWorld(),
                damaged.getYaw(), damaged.getPitch()
        );
        if (damaged instanceof ServerPlayerEntity playerVictim) {
            return getHandler().cancelOperation(Operation.of(
                    getUser(attacker),
                    getUser(playerVictim),
                    OperationType.PLAYER_DAMAGE_PLAYER,
                    damagedPos
            )) ? ActionResult.FAIL : ActionResult.PASS;
        }

        return getHandler().cancelOperation(Operation.of(
                getUser(attacker),
                getPlayerDamageType(damaged),
                damagedPos
        )) ? ActionResult.FAIL : ActionResult.PASS;
    }

    private boolean isBlockedCrossClaimExplosion(@NotNull Entity source, @NotNull Entity target) {
        final OperationPosition sourcePosition = getPosition(source.getPos(), source.getWorld(), source.getYaw(), source.getPitch());
        final OperationPosition targetPosition = getPosition(target.getPos(), target.getWorld(), target.getYaw(), target.getPitch());
        return plugin.cancelNature(targetPosition.getWorld(), sourcePosition, targetPosition);
    }

    private boolean isBlockedHostileProjectileDamage(@NotNull Entity source, @NotNull Entity target) {
        final OperationPosition targetPosition = getPosition(target.getPos(), target.getWorld(), target.getYaw(), target.getPitch());
        return getHandler().cancelOperation(Operation.of(
                OperationType.MONSTER_DAMAGE_TERRAIN,
                targetPosition
        )) || getHandler().cancelNature(
                targetPosition.getWorld(),
                getPosition(source.getPos(), source.getWorld(), source.getYaw(), source.getPitch()),
                targetPosition
        );
    }

    @NotNull
    private OperationType getPlayerDamageType(@NotNull Entity entity) {
        OperationType type = OperationType.PLAYER_DAMAGE_ENTITY;
        if (isMonster(entity)) {
            type = OperationType.PLAYER_DAMAGE_MONSTER;
        } else if (entity instanceof VehicleEntity vehicle) {
            type = vehicle instanceof VehicleInventory ? OperationType.BLOCK_BREAK : OperationType.BREAK_VEHICLE;
        } else if (entity instanceof AbstractDecorationEntity) {
            type = OperationType.BREAK_HANGING_ENTITY;
        } else if (!(entity instanceof LivingEntity)) {
            type = OperationType.BLOCK_BREAK;
        } else if (entity.hasCustomName()) {
            type = OperationType.PLAYER_DAMAGE_PERSISTENT_ENTITY;
        }
        return type;
    }

    @Override
    @NotNull
    public OperationPosition getPosition(@NotNull Vec3d vec3d, @NotNull net.minecraft.world.World world,
                                         float yaw, float pitch) {
        return FabricHuskClaims.Adapter.adapt(world, vec3d, yaw, pitch);
    }

    @Override
    @NotNull
    public OperationUser getUser(@NotNull PlayerEntity player) {
        return plugin.getOnlineUser((ServerPlayerEntity) player);
    }

    @Override
    public void setInspectionDistance(int i) {
        throw new UnsupportedOperationException("Cannot change inspection distance");
    }

}
