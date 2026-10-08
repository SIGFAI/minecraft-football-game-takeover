package sigf.mod;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

/** Football Game Takeover: a stadium appears around you, two teams of footballers charge, you throw the football. */
public final class SigfMod implements ModInitializer {
	public static Item FOOTBALL;
	public static SoundEvent WHISTLE, CHEER;

	static final int RED = 0xC8201E, BLUE = 0x1E50C8;
	static final int GOAL_X = 27, FIELD_X = 22, FIELD_Z = 13;
	static final String[] POS_RED = {"Linebacker", "Tackle", "Safety", "Blitzer", "Receiver", "Lineman"};
	static final String[] POS_BLUE = {"Cornerback", "Guard", "Runner", "Rusher", "Center", "Punter"};

	static boolean built;
	static BlockPos origin;      // midfield, on the floor surface (feet level)
	static int score;
	static int streak;
	static final List<Ball> balls = new ArrayList<>();

	static final class Ball {
		final Snowball e; final ServerPlayer owner; Vec3 last; boolean goalCounted;
		Ball(Snowball e, ServerPlayer owner) { this.e = e; this.owner = owner; this.last = e.position(); }
	}

	@Override
	public void onInitialize() {
		FOOTBALL = Sigf.item("football", p -> new Item(p.stacksTo(1)));
		WHISTLE = Sigf.registerSound("whistle");
		CHEER = Sigf.registerSound("cheer");

		// Right click: throw the football. It is never used up.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack st = player.getItemInHand(hand);
			if (!st.is(FOOTBALL)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp && !sp.getCooldowns().isOnCooldown(st)) {
				sp.getCooldowns().addCooldown(st, 10);
				Vec3 look = sp.getLookAngle();
				throwBall(sp, look.scale(1.9));
			}
			return InteractionResult.SUCCESS;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> tickBalls());

		ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
			if (e.entityTags().contains("sigf_footballer")) touchdown(e);
		});

		// Build the stadium as soon as a player exists.
		Sigf.every(0.5, () -> {
			if (built || Sigf.host() == null) return;
			built = true;
			buildStadium();
		});

		// Waves of footballers. Always running, so it is playable without the demo.
		Sigf.every(9, () -> { if (built) wave(Sigf.isDemo() ? 3 : 4); });

		// Teams fight each other too.
		Sigf.every(2, () -> {
			if (!built) return;
			for (Entity e : Sigf.near(mid(), 40, x -> x.entityTags().contains("sigf_footballer"))) {
				if (!(e instanceof Mob m) || m.getTarget() instanceof Monster) continue;
				if (Sigf.level().getRandom().nextInt(2) == 0) continue;
				boolean red = m.entityTags().contains("sigf_red");
				for (Entity o : Sigf.near(m.position(), 9, x -> x.entityTags().contains(red ? "sigf_blue" : "sigf_red"))) {
					m.setTarget((LivingEntity) o);
					break;
				}
			}
		});

		// Scoreboard on the action bar.
		Sigf.every(1, () -> {
			for (ServerPlayer p : Sigf.players()) p.sendSystemMessage(Component.literal("SCORE " + score + "   STREAK x" + streak), true);
		});

		// Keep it a sunny game day.
		Sigf.after(1, () -> { Sigf.command("time set noon"); Sigf.command("weather clear"); Sigf.command("gamerule advance_time false"); });
		Sigf.every(5, () -> {
			if (!built) return;
			for (ServerPlayer p : Sigf.players()) if (!p.getInventory().contains(s -> s.is(FOOTBALL)))
				p.getInventory().add(new ItemStack(FOOTBALL));
		});

		// ---- Demo (75 s) ----
		Sigf.demo(2, () -> {
			Sigf.title("FOOTBALL GAME TAKEOVER", "Kickoff!", 3);
			Sigf.sound(WHISTLE, Sigf.host().position(), 1f, 1f);
			Sigf.sound(CHEER, Sigf.host().position(), 1f, 1f);
			Sigf.lookAt(Sigf.host(), mid().add(FIELD_X, 3, 0));
		});
		Sigf.demo(4, () -> wave(4));
		Sigf.demo(9, () -> aimAtNearest(true));
		Sigf.demo(11, () -> aimAtNearest(true));
		Sigf.demo(13, () -> aimAtNearest(true));
		Sigf.demo(18, () -> fieldGoal(GOAL_X));
		Sigf.demo(24, () -> { Sigf.lookAt(Sigf.host(), mid().add(0, 2, 6)); wave(4); });
		Sigf.demo(30, () -> aimAtNearest(true));
		Sigf.demo(33, () -> aimAtNearest(true));
		Sigf.demo(36, () -> aimAtNearest(true));
		Sigf.demo(42, () -> fieldGoal(-GOAL_X));
		Sigf.demo(48, () -> wave(5));
		Sigf.demo(53, () -> aimAtNearest(true));
		Sigf.demo(56, () -> aimAtNearest(true));
		Sigf.demo(59, () -> aimAtNearest(true));
		Sigf.demo(64, () -> fieldGoal(GOAL_X));
		Sigf.demo(70, () -> aimAtNearest(true));
	}

	static Vec3 mid() { return origin == null ? Sigf.host().position() : Vec3.atBottomCenterOf(origin); }

	// ---------------------------------------------------------------- the ball

	static void throwBall(ServerPlayer p, Vec3 vel) {
		ItemStack look = new ItemStack(FOOTBALL);
		Snowball b = new Snowball(Sigf.level(), p, look);
		b.setPos(p.getEyePosition().add(vel.normalize().scale(0.6)).add(0, -0.15, 0));
		b.setDeltaMovement(vel);
		Sigf.level().addFreshEntity(b);
		balls.add(new Ball(b, p));
		Sigf.sound(SoundEvents.SNOWBALL_THROW, p.position(), 1f, 0.7f);
	}

	static void aimAtNearest(boolean hit) {
		ServerPlayer p = Sigf.host();
		Entity best = null; double bd = 1e9;
		for (Entity e : Sigf.near(p.position(), 14, x -> x.entityTags().contains("sigf_footballer"))) {
			double d = e.position().distanceTo(p.position());
			if (d < bd) { bd = d; best = e; }
		}
		if (best == null) return;
		Vec3 target = best.position().add(0, 1.0, 0);
		Sigf.lookAt(p, target);
		Vec3 d = target.subtract(p.getEyePosition());
		double speed = 1.9;
		double t = d.length() / speed;
		Vec3 v = d.normalize().scale(speed).add(0, 0.5 * 0.03 * t, 0);
		throwBall(p, v);
	}

	static void fieldGoal(int goalX) {
		ServerPlayer p = Sigf.host();
		Vec3 target = mid().add(goalX, 6.2, 0);
		Sigf.lookAt(p, target);
		Vec3 d = target.subtract(p.getEyePosition());
		double speed = 1.9;
		double steps = d.length() / speed;
		Vec3 v = d.normalize().scale(speed).add(0, 0.5 * 0.03 * steps * 1.0, 0);
		throwBall(p, v);
		Sigf.sound(WHISTLE, p.position(), 0.8f, 1.2f);
	}

	static void tickBalls() {
		if (balls.isEmpty() || !built) return;
		List<Ball> done = new ArrayList<>();
		for (Ball b : balls) {
			Vec3 pos = b.e.isRemoved() ? b.last : b.e.position();
			Vec3 prev = b.last;
			if (!b.e.isRemoved()) {
				Sigf.particles(ParticleTypes.CRIT, pos, 2, 0.05);
				b.e.setYRot(b.e.getYRot() + 40);
			}
			// field goal: the ball crosses a goal plane between the uprights and above the bar
			double gx = origin.getX() + 0.5 + GOAL_X;
			for (double plane : new double[] {gx, origin.getX() + 0.5 - GOAL_X}) {
				boolean crossed = (prev.x - plane) * (pos.x - plane) <= 0 && prev.x != pos.x;
				if (crossed && !b.goalCounted) {
					double f = (plane - prev.x) / (pos.x - prev.x);
					double y = prev.y + (pos.y - prev.y) * f, z = prev.z + (pos.z - prev.z) * f;
					if (Math.abs(z - (origin.getZ() + 0.5)) < 3.0 && y > origin.getY() + 3 && y < origin.getY() + 11) {
						b.goalCounted = true;
						score += 3; streak++;
						Sigf.title("FIELD GOAL! +3", "It is GOOD!", 2);
						Sigf.sound(CHEER, mid(), 1f, 1f);
						celebrate(new Vec3(plane, origin.getY() + 6, origin.getZ() + 0.5));
					}
				}
			}
			// tackle: hit a footballer
			Entity hitE = null;
			for (Entity e : Sigf.near(pos, 1.6, x -> x.entityTags().contains("sigf_footballer"))) { hitE = e; break; }
			if (hitE instanceof LivingEntity le) {
				Vec3 dir = new Vec3(pos.x - prev.x, 0, pos.z - prev.z);
				if (dir.lengthSqr() < 1e-4) dir = le.position().subtract(b.owner.position());
				dir = new Vec3(dir.x, 0, dir.z).normalize();
				le.hurtServer(Sigf.level(), Sigf.level().damageSources().playerAttack(b.owner), 8f);
				Sigf.teleport(le, le.position().add(0, 0.1, 0), dir.scale(1.4).add(0, 0.55, 0));
				Sigf.particles(ParticleTypes.CRIT, le.position().add(0, 1, 0), 25, 0.4);
				Sigf.particles(ParticleTypes.POOF, le.position().add(0, 0.5, 0), 12, 0.3);
				Sigf.sound(SoundEvents.PLAYER_ATTACK_STRONG, le.position(), 1.2f, 0.7f);
				score += 1;
				if (!b.e.isRemoved()) b.e.discard();
				done.add(b);
				continue;
			}
			b.last = pos;
			if (b.e.isRemoved()) {
				Sigf.particles(ParticleTypes.POOF, pos, 6, 0.15);
				done.add(b);
			}
		}
		balls.removeAll(done);
	}

	static void touchdown(Entity e) {
		score += 6; streak++;
		Vec3 p = e.position();
		Sigf.sound(CHEER, p, 0.8f, 1f);
		Sigf.particles(ParticleTypes.FIREWORK, p.add(0, 1.5, 0), 40, 0.6);
		Sigf.title("TOUCHDOWN!", "+6 points", 1.5);
		Sigf.command("summon firework_rocket " + p.x + " " + (p.y + 1) + " " + p.z
			+ " {LifeTime:12,FireworksItem:{id:\"firework_rocket\",count:1,components:{\"minecraft:fireworks\":{explosions:[{shape:\"star\",colors:[I;"
			+ (e.entityTags().contains("sigf_red") ? RED : BLUE) + ",16755200],fade_colors:[16777215]}],flight_duration:1}}}}");
	}

	static void celebrate(Vec3 p) {
		Sigf.particles(ParticleTypes.FIREWORK, p, 60, 1.5);
		Sigf.particles(ParticleTypes.HAPPY_VILLAGER, p, 20, 1.5);
		Sigf.command("summon firework_rocket " + p.x + " " + (p.y - 2) + " " + p.z
			+ " {LifeTime:10,FireworksItem:{id:\"firework_rocket\",count:1,components:{\"minecraft:fireworks\":{explosions:[{shape:\"large_ball\",colors:[I;16755200,16777215],fade_colors:[16711680]}],flight_duration:1}}}}");
	}

	// ---------------------------------------------------------------- the players

	static ItemStack dyed(Item item, int rgb) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
		return s;
	}

	static void wave(int n) {
		ServerPlayer host = Sigf.host();
		if (host == null || origin == null) return;
		int alive = Sigf.near(mid(), 45, x -> x.entityTags().contains("sigf_footballer")).size();
		if (alive > 14) return;
		Sigf.sound(WHISTLE, host.position(), 1f, 1f);
		var r = Sigf.level().getRandom();
		for (int i = 0; i < n * 2; i++) {
			boolean red = (i % 2 == 0);
			double sx = host.getX() + (red ? 1 : -1) * (10 + r.nextInt(8));
			double sz = origin.getZ() + 0.5 + (r.nextDouble() - 0.5) * 20;
			sx = Math.max(origin.getX() - FIELD_X + 2, Math.min(origin.getX() + FIELD_X - 2, sx));
			spawnPlayer(new Vec3(sx, origin.getY(), sz), red);
		}
	}

	static void spawnPlayer(Vec3 pos, boolean red) {
		var r = Sigf.level().getRandom();
		Mob m = red ? Sigf.spawn(EntityTypes.ZOMBIE, pos) : Sigf.spawn(EntityTypes.HUSK, pos);
		if (m == null) return;
		int rgb = red ? RED : BLUE;
		m.addTag("sigf_footballer");
		m.addTag(red ? "sigf_red" : "sigf_blue");
		m.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, rgb));
		m.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, rgb));
		m.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0xF0F0F0));
		m.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, rgb));
		m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FOOTBALL));
		for (EquipmentSlot s : EquipmentSlot.values()) m.setDropChance(s, 0f);
		m.setPersistenceRequired();
		String[] names = red ? POS_RED : POS_BLUE;
		m.setCustomName(Component.literal("#" + (1 + r.nextInt(98)) + " " + names[r.nextInt(names.length)]));
		m.setCustomNameVisible(true);
		boolean big = r.nextInt(4) == 0;
		m.getAttribute(Attributes.SCALE).setBaseValue(big ? 1.35 : 1.0);
		m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(big ? 30 : 20);
		m.setHealth(big ? 30f : 20f);
		m.getAttribute(Attributes.ATTACK_KNOCKBACK).setBaseValue(big ? 2.5 : 1.5);
		m.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Sigf.isPlayer() ? 2.5 : 1);
		m.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(big ? 0.22 : 0.3);
		Sigf.particles(ParticleTypes.POOF, pos.add(0, 1, 0), 10, 0.3);
	}

	// ---------------------------------------------------------------- the stadium

	static void set(int x, int y, int z, Block b) {
		Sigf.level().setBlock(new BlockPos(x, y, z), b.defaultBlockState(), Block.UPDATE_CLIENTS);
	}

	static void buildStadium() {
		var lvl = Sigf.level();
		Vec3 c = Sigf.host().position();
		BlockPos top = lvl.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(c));
		int y0 = top.getY();                       // feet level
		int cx = top.getX(), cz = top.getZ();
		origin = new BlockPos(cx, y0, cz);
		var rnd = lvl.getRandom();

		for (int dx = -GOAL_X - 4; dx <= GOAL_X + 4; dx++) {
			for (int dz = -24; dz <= 24; dz++) {
				int x = cx + dx, z = cz + dz;
				for (int y = y0 - 4; y < y0; y++) set(x, y, z, Blocks.DIRT);
				for (int y = y0; y <= y0 + 14; y++) set(x, y, z, Blocks.AIR);
				int adx = Math.abs(dx), adz = Math.abs(dz);
				Block floor;
				if (adz > FIELD_Z + 1 || adx > GOAL_X + 1) floor = Blocks.CONCRETE.gray();      // concourse
				else if (adz == FIELD_Z + 1 || adx == GOAL_X + 1) floor = Blocks.CONCRETE.white();
				else if (adx > FIELD_X) floor = dx > 0 ? Blocks.CONCRETE.blue() : Blocks.CONCRETE.red();
				else floor = (Math.floorDiv(dx + 2, 5) % 2 == 0) ? Blocks.CONCRETE.lime() : Blocks.CONCRETE.green();
				if (adz <= FIELD_Z && adx <= FIELD_X) {
					if (adx == FIELD_X || adz == FIELD_Z) floor = Blocks.CONCRETE.white();       // boundary lines
					else if (dx % 5 == 0) floor = Blocks.CONCRETE.white();                       // yard lines
					// (no hash marks)
					int mr = dx * dx + dz * dz;
					if (mr <= 36 && mr >= 25) floor = Blocks.CONCRETE.yellow();                  // midfield logo ring
					if (mr < 5) floor = Blocks.CONCRETE.orange();
				}
				set(x, y0 - 1, z, floor);
			}
		}

		// stands on both long sides
		for (int side = -1; side <= 1; side += 2) {
			for (int dx = -FIELD_X - 5; dx <= FIELD_X + 5; dx++) {
				for (int tier = 0; tier < 7; tier++) {
					int z = cz + side * (FIELD_Z + 3 + tier);
					for (int h = 0; h <= tier + 1; h++) {
						Block b = Blocks.CONCRETE.gray();
						if (h == tier + 1) {
							int k = rnd.nextInt(6);
							b = switch (k) { case 0, 1 -> Blocks.WOOL.red(); case 2, 3 -> Blocks.WOOL.blue(); case 4 -> Blocks.WOOL.white(); default -> Blocks.WOOL.yellow(); };
						}
						set(cx + dx, y0 + h, z, b);
					}
				}
				// back wall rail
				set(cx + dx, y0 + 9, cz + side * (FIELD_Z + 10), Blocks.CONCRETE.lightGray());
			}
			// sideline benches / coach walls
			for (int dx = -10; dx <= 10; dx++) set(cx + dx, y0, cz + side * (FIELD_Z + 2), Blocks.DYED_TERRACOTTA.red());
		}

		// goal posts at both ends
		for (int side = -1; side <= 1; side += 2) {
			int gx = cx + side * GOAL_X;
			for (int y = y0; y <= y0 + 2; y++) set(gx, y, cz, Blocks.CONCRETE.yellow());     // stem
			for (int dz = -3; dz <= 3; dz++) set(gx, y0 + 3, cz + dz, Blocks.CONCRETE.yellow()); // crossbar
			for (int y = y0 + 4; y <= y0 + 11; y++) { set(gx, y, cz - 3, Blocks.CONCRETE.yellow()); set(gx, y, cz + 3, Blocks.CONCRETE.yellow()); }
			set(gx, y0 + 12, cz - 3, Blocks.GOLD_BLOCK); set(gx, y0 + 12, cz + 3, Blocks.GOLD_BLOCK);
		}

		// floodlight towers at the four corners
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
			int tx = cx + sx * (GOAL_X + 3), tz = cz + sz * 22;
			for (int y = y0; y <= y0 + 14; y++) set(tx, y, tz, Blocks.CONCRETE.lightGray());
			for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) set(tx + a, y0 + 15, tz + b, Blocks.SEA_LANTERN);
		}

		// put the player at midfield facing the blue end
		Sigf.after(0.2, () -> {
			if (!Sigf.isDemo() && !Sigf.isPlayer()) return;
			for (ServerPlayer p : Sigf.players()) {
				Sigf.teleport(p, new Vec3(cx + 0.5, y0, cz + 0.5), Vec3.ZERO);
				Sigf.lookAt(p, new Vec3(cx + FIELD_X, y0 + 3, cz + 0.5));
			}
		});
		Sigf.after(1.2, () -> wave(3));
	}
}
