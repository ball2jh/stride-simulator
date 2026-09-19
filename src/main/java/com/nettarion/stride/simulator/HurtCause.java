package com.nettarion.stride.simulator;

/**
 * The damage source of one server hit on the player, as the server's
 * {@code DamageSources} names it.
 *
 * <p>Every source here is in vanilla's {@code no_knockback} damage-type tag,
 * so a full hit adds no velocity of its own; every source but
 * {@link #DROWN} is outside {@code no_impact}, so its full hit calls
 * {@code markHurt} and {@code ServerEntity} then republishes the server's
 * current velocity at the next publication. A source that changes velocity
 * (attack knockback, an explosion's impulse) is an {@link ImpulseWrite}, not
 * a hurt cause.
 */
public enum HurtCause {
	/** {@code Entity.checkFallDamage} on the accepted movement packet's landing. */
	FALL,
	/** The same landing on an upward pointed dripstone tip: the fall plus two and a half, doubled. */
	STALAGMITE,
	/**
	 * {@code LivingEntity.handleFallFlyingCollisions} in the server's own
	 * glide travel: a horizontal collision that shed more than three tenths
	 * of a block per tick of horizontal speed.
	 */
	FLY_INTO_WALL,
	/** {@code CactusBlock.entityInside}: one point on every visit. */
	CACTUS,
	/** {@code SweetBerryBushBlock.entityInside}: one point on a grown bush while moving horizontally. */
	SWEET_BERRY_BUSH,
	/** {@code MagmaBlock.stepOn}: one point underfoot unless stepping carefully. */
	HOT_FLOOR,
	/** {@code CampfireBlock.entityInside}: one point on a lit campfire, two on a soul campfire. */
	CAMPFIRE,
	/** {@code BaseFireBlock.entityInside}: one point in fire, two in soul fire, after the ignition effect. */
	IN_FIRE,
	/** {@code Entity.baseTick}: one point every twentieth burning tick outside lava. */
	ON_FIRE,
	/** {@code Entity.lavaHurt}: four points on every lava or lava cauldron visit. */
	LAVA,
	/** {@code LivingEntity.baseTick}: two points when the air supply runs out, and nothing marked. */
	DROWN,
	/** The freezing tail: one point every fortieth entity tick while fully frozen. */
	FREEZE,
	/** {@code LivingEntity.baseTick}: one point every tick the eyes are inside a suffocating block. */
	IN_WALL,
	/** {@code Entity.checkBelowWorld}: four points every tick sixty-four blocks below the world. */
	FELL_OUT_OF_WORLD,
	/** FoodData.tick: one point every eightieth starving tick above the difficulty threshold. */
	STARVE;

	/** The pinned damage type's exhaustion, charged only for damage past absorption. */
	public float foodExhaustion() {
		return switch (this) {
			case CACTUS, SWEET_BERRY_BUSH, HOT_FLOOR, CAMPFIRE, IN_FIRE, LAVA -> 0.1F;
			case FALL, STALAGMITE, FLY_INTO_WALL, ON_FIRE, DROWN, FREEZE, IN_WALL, FELL_OUT_OF_WORLD, STARVE -> 0.0F;
		};
	}

	/** Whether a full hit calls {@code markHurt}: every source outside {@code no_impact}. */
	public boolean marksHurt() {
		return this != DROWN;
	}

	/**
	 * Whether the pinned {@code bypasses_invulnerability} tag holds this type,
	 * so {@code Player.hurtServer} deals it to an invulnerable player too.
	 * Falling out of the world is the only admitted member.
	 */
	public boolean bypassesInvulnerability() {
		return this == FELL_OUT_OF_WORLD;
	}

	/**
	 * Whether the level's damage gamerules admit this cause: {@code Player.isInvulnerableTo}
	 * by the pinned damage type tags, where fall and stalagmite are falls, in-fire, campfire,
	 * on-fire, lava and hot-floor are fire, and the rest answer to no rule.
	 */
	public boolean permittedBy(final ServerPlayerState server) {
		return switch (this) {
			case FALL, STALAGMITE -> server.fallDamage;
			case IN_FIRE, CAMPFIRE, ON_FIRE, LAVA, HOT_FLOOR -> server.fireDamage;
			case DROWN -> server.drowningDamage;
			case FREEZE -> server.freezeDamage;
			case FLY_INTO_WALL, CACTUS, SWEET_BERRY_BUSH, IN_WALL, FELL_OUT_OF_WORLD, STARVE -> true;
		};
	}
}
