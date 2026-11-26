package com.greysworkshop.totembuilder.mixin;

import com.greysworkshop.totembuilder.Look;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts the printer's look into the tick's own movement packet, and gives your view straight back after. */
@Mixin(ClientPlayerEntity.class)
abstract class SendLook {
	@Unique private float totembuilder$yaw, totembuilder$pitch;
	@Unique private boolean totembuilder$swapped;

	@Inject(method = "sendMovementPackets", at = @At("HEAD"))
	private void totembuilder$before(CallbackInfo ci) {
		totembuilder$swapped = Look.active();
		if (!totembuilder$swapped) return;
		ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
		totembuilder$yaw = self.getYaw();
		totembuilder$pitch = self.getPitch();
		self.setYaw(Look.yaw());
		self.setPitch(Look.pitch());
	}

	@Inject(method = "sendMovementPackets", at = @At("RETURN"))
	private void totembuilder$after(CallbackInfo ci) {
		if (!totembuilder$swapped) return;
		totembuilder$swapped = false;
		ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
		self.setYaw(totembuilder$yaw);
		self.setPitch(totembuilder$pitch);
	}
}
