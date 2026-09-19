/**
 * The player tick as a sequence of phases: the transcription of vanilla's {@code Entity},
 * {@code LivingEntity}, {@code Player} and {@code LocalPlayer} tick bodies, shared by the client's
 * copy of the player and the server's copy.
 *
 * <p>This package is internal: it is not exported by the module and its types may change between
 * releases. Consumers step through {@code Simulator} in the root package.
 *
 * <p>A phase is one class with a {@code run} method that reads a {@code PlayerState}, a
 * {@code WorldView} and a {@code Scratch} and writes only the state fields it declares in its
 * {@code WRITES} set. {@link com.nettarion.stride.simulator.tick.PlayerTick} runs the phases in
 * vanilla's order: {@link com.nettarion.stride.simulator.tick.BaseTick}, then
 * {@link com.nettarion.stride.simulator.tick.AiStep}, then
 * {@link com.nettarion.stride.simulator.tick.Travel} (which runs
 * {@link com.nettarion.stride.simulator.tick.Move} once per travel branch), then the block
 * effects in the {@code block} package, then {@link com.nettarion.stride.simulator.tick.Freezing},
 * then {@code LocalPlayer.aiStep}'s tail, then
 * {@link com.nettarion.stride.simulator.tick.PlayerPose}.
 * {@link com.nettarion.stride.simulator.tick.ClientTick} is the client-authority entry.
 * {@link com.nettarion.stride.simulator.tick.EntityFluidInteraction},
 * {@link com.nettarion.stride.simulator.tick.SupportingBlock} and the package-private
 * {@code Clearance} are the fluid, support and pose-fit queries the phases share.
 * {@link com.nettarion.stride.simulator.tick.Scratch} is the per-thread buffer set.
 *
 * <p>Identifiers in {@code code} font are vanilla's Mojang-mapped names, and a class named for a
 * vanilla method transcribes that method. Glossary: {@code xxa}, {@code yya} and {@code zza} are
 * the sideways, vertical and forward movement input after {@code LivingEntity.applyInput}, in
 * unit-square coordinates; {@code yRot} and {@code xRot} are yaw and pitch in degrees;
 * {@code aiStep} is the per-tick control body ({@code LivingEntity.aiStep}); {@code travel} is the
 * velocity update from the medium ({@code LivingEntity.travel}). Positions and boxes are in blocks,
 * velocities in blocks per tick, counters in ticks.
 *
 * <p>Arithmetic, rounding order and phase order are vanilla's; static composition replaces the
 * game's inheritance graph and flat state replaces its objects. Server authority questions go
 * through the server package's {@code TickAuthority}; nothing here sends a packet or decides damage.
 */
package com.nettarion.stride.simulator.tick;
