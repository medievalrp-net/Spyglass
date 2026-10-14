package regression.probe;

import java.lang.reflect.*;
import java.nio.file.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated integration probe: calls vanilla interaction methods on real server entities. Never deploy. */
public final class LoggingProbe extends JavaPlugin {
    private Player player;
    private World world;
    private Ageable cow;
    private LivingEntity cube;
    private Entity cushion;
    private Path marker;
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player || args.length != 1) return false;
        if (args[0].equals("state") && world != null) {
            var cushions = world.getNearbyEntities(new Location(world,116.5,80,100.5),1,1,1).stream()
                    .filter(e->e.getType().name().equals("CUSHION")).toList();
            sender.sendMessage("CUSHIONS " + cushions.size() + " " + cushions.stream()
                    .map(e->((org.bukkit.material.Colorable)e).getColor().name()).toList());
            return true;
        }
        player = Bukkit.getPlayerExact(args[0]);
        if (player == null) return false;
        world = player.getWorld();
        marker = Path.of("logging-probe-result.txt");
        try {
            Files.writeString(marker, "RUNNING");
            for (Entity entity : world.getNearbyEntities(new Location(world,116,82,102),6,6,6))
                if (!(entity instanceof Player)) entity.remove();
            for (int x=111;x<=120;x++) for (int z=98;z<=106;z++) {
                world.getBlockAt(x,79,z).setType(Material.STONE);
                for (int y=80;y<=86;y++) world.getBlockAt(x,y,z).setType(Material.AIR);
            }
            player.setGameMode(GameMode.CREATIVE);
            player.teleport(new Location(world,112.5,80,104.5));
            cow = (Ageable) world.spawnEntity(new Location(world,112.5,80,100.5), EntityType.COW);
            cow.setBaby(); ((Mob)cow).setAI(false);
            interact(cow, "GOLDEN_DANDELION");
            later(50, () -> {
                check(cow.getAgeLock(), "native golden-dandelion lock");
                interact(cow, "GOLDEN_DANDELION");
                later(5, () -> {
                    check(!cow.getAgeLock(), "native golden-dandelion unlock"); cow.remove(); sulfur();
                });
            });
        } catch (Throwable error) { fail(error); }
        return true;
    }
    private void sulfur() throws Exception {
        if (Material.getMaterial("SULFUR_CUBE_BUCKET") == null) { decoration(); return; }
        cube = (LivingEntity) world.spawnEntity(new Location(world,114.5,80,100.5), EntityType.valueOf("SULFUR_CUBE"));
        ((Ageable)cube).setAdult(); ((Mob)cube).setAI(false);
        interact(cube,"STONE");
        later(5, () -> {
            check(cube.getEquipment().getItem(EquipmentSlot.BODY).getType() == Material.STONE, "native sulfur absorption");
            interact(cube,"SHEARS");
            later(5, () -> {
                check(cube.getEquipment().getItem(EquipmentSlot.BODY).getType().isAir(), "native sulfur shearing");
                interact(cube,"TNT");
                later(5, () -> {
                    interact(cube,"FLINT_AND_STEEL");
                    later(3, () -> {
                        check((Integer)cube.getClass().getMethod("getFuseTicks").invoke(cube) > 0, "native sulfur ignition");
                        cube.remove(); growthAndBucket();
                    });
                });
            });
        });
    }
    private void growthAndBucket() throws Exception {
        cube = (LivingEntity) world.spawnEntity(new Location(world,114.5,80,103.5), EntityType.valueOf("SULFUR_CUBE"));
        ((Mob)cube).setAI(false);
        ((Ageable)cube).setBaby();
        int oldAge=((Ageable)cube).getAge();
        interact(cube,"SLIME_BALL");
        later(5, () -> {
            check(((Ageable)cube).getAge()>oldAge+1,"native sulfur growth feeding");
            ((Ageable)cube).setAdult();
            interact(cube,"BUCKET");
            later(5, () -> {
                check(!cube.isValid(),"native sulfur bucket capture");
                ItemStack bucket=java.util.Arrays.stream(player.getInventory().getContents())
                        .filter(i->i!=null && i.getType().name().equals("SULFUR_CUBE_BUCKET")).findFirst().orElseThrow();
                player.teleport(new Location(world,114.5,81,105.5,0,90));
                player.getInventory().setItemInMainHand(bucket.clone());
                useHeld(world.getBlockAt(114,79,105));
                later(5, () -> {
                    check(world.getNearbyEntities(new Location(world,114.5,80,105.5),.7,1,.7).stream()
                            .anyMatch(e->e.getType().name().equals("SULFUR_CUBE")),"native sulfur bucket release");
                    decoration();
                });
            });
        });
    }
    private void decoration() throws Exception {
        if (Material.getMaterial("RED_CUSHION") == null) { if (Material.getMaterial("SULFUR_SPIKE") != null) spikes(); else finish(); return; }
        use(world.getBlockAt(116,79,100), "RED_CUSHION");
        later(5, () -> {
            cushion = world.getNearbyEntities(new Location(world,116.5,80,100.5),1,1,1).stream()
                    .filter(e -> e.getType().name().equals("CUSHION")).findFirst().orElseThrow();
            check(((org.bukkit.material.Colorable)cushion).getColor()==DyeColor.RED, "native red cushion placement");
            Object ph = handle(player), eh = handle(cushion);
            Object sources = ph.getClass().getMethod("damageSources").invoke(ph);
            Object damage = sources.getClass().getMethod("playerAttack", Class.forName("net.minecraft.world.entity.player.Player")).invoke(sources, ph);
            method(eh.getClass(), "hurtServer", 3).invoke(eh, handle(world), damage, 1f);
            later(5, () -> { check(!cushion.isValid(), "native cushion destruction"); straw(); });
        });
    }
    private void straw() throws Exception {
        Block foot=world.getBlockAt(116,80,103), head=world.getBlockAt(116,80,102);
        foot.setBlockData(Bukkit.createBlockData("minecraft:straw_bed[facing=north,part=foot,occupied=false]"), false);
        head.setBlockData(Bukkit.createBlockData("minecraft:straw_bed[facing=north,part=head,occupied=false]"), false);
        check(player.sleep(head.getLocation(), true), "straw bed accepted sleeping player");
        later(5, () -> {
            if (player.isSleeping()) player.wakeup(false);
            later(5, () -> {
                check(foot.getType().isAir() && head.getType().isAir(), "native straw-bed consumption removes both halves");
                spikes();
            });
        });
    }
    private void spikes() throws Exception {
        Block floor=world.getBlockAt(118,79,100), ceiling=world.getBlockAt(118,84,102);
        ceiling.setType(Material.STONE);
        world.getBlockAt(118,80,100).setBlockData(Bukkit.createBlockData("minecraft:sulfur_spike[vertical_direction=up,thickness=frustum,waterlogged=false]"),false);
        world.getBlockAt(118,81,100).setBlockData(Bukkit.createBlockData("minecraft:sulfur_spike[vertical_direction=up,thickness=tip,waterlogged=false]"),false);
        world.getBlockAt(118,83,102).setBlockData(Bukkit.createBlockData("minecraft:sulfur_spike[vertical_direction=down,thickness=frustum,waterlogged=false]"),false);
        world.getBlockAt(118,82,102).setBlockData(Bukkit.createBlockData("minecraft:sulfur_spike[vertical_direction=down,thickness=tip,waterlogged=false]"),false);
        check(player.breakBlock(floor) && player.breakBlock(ceiling), "native sulfur-spike support breaks");
        later(20, () -> {
            check(world.getBlockAt(118,80,100).getType().isAir() && world.getBlockAt(118,83,102).getType().isAir(), "sulfur columns disappeared");
            finish();
        });
    }
    private void interact(Entity entity,String item) throws Exception {
        player.getInventory().setItemInMainHand(new ItemStack(Material.valueOf(item)));
        // The packet handler dispatches this before the vanilla entity interaction.
        var event = new org.bukkit.event.player.PlayerInteractEntityEvent(player,entity,EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) throw new AssertionError("interaction cancelled");
        Object hand=enumValue("net.minecraft.world.InteractionHand","MAIN_HAND");
        Object target = handle(entity);
        Method interaction = java.util.Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().equals("interact") && m.getParameterCount() >= 2).findFirst().orElseThrow();
        if (interaction.getParameterCount() == 2) interaction.invoke(target,handle(player),hand);
        else interaction.invoke(target,handle(player),hand,Class.forName("net.minecraft.world.phys.Vec3")
                .getConstructor(double.class,double.class,double.class).newInstance(0d,0d,0d));
    }
    private void use(Block support,String item) throws Exception {
        player.getInventory().setItemInMainHand(new ItemStack(Material.valueOf(item)));
        useHeld(support);
    }
    private void useHeld(Block support) throws Exception {
        Object ph=handle(player), hand=enumValue("net.minecraft.world.InteractionHand","MAIN_HAND");
        Class<?> vec=Class.forName("net.minecraft.world.phys.Vec3"), pos=Class.forName("net.minecraft.core.BlockPos"), dir=Class.forName("net.minecraft.core.Direction");
        Object hit=Class.forName("net.minecraft.world.phys.BlockHitResult").getConstructor(vec,dir,pos,boolean.class).newInstance(
                vec.getConstructor(double.class,double.class,double.class).newInstance(support.getX()+.5,support.getY()+1d,support.getZ()+.5),
                enumValue(dir.getName(),"UP"),pos.getConstructor(int.class,int.class,int.class).newInstance(support.getX(),support.getY(),support.getZ()),false);
        Object stack=ph.getClass().getMethod("getMainHandItem").invoke(ph);
        Object mode=ph.getClass().getField("gameMode").get(ph);
        method(mode.getClass(),"useItemOn",5).invoke(mode,ph,handle(world),stack,hand,hit);
        if (player.getInventory().getItemInMainHand().getType().name().endsWith("_BUCKET"))
            method(mode.getClass(),"useItem",4).invoke(mode,ph,handle(world),stack,hand);
    }
    private static Object handle(Object object) throws Exception { return object.getClass().getMethod("getHandle").invoke(object); }
    @SuppressWarnings({"rawtypes","unchecked"}) private static Object enumValue(String type,String name) throws Exception { return Enum.valueOf((Class)Class.forName(type),name); }
    private static Method method(Class<?> type,String name,int count) {
        return java.util.Arrays.stream(type.getMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==count).findFirst().orElseThrow();
    }
    private void later(long ticks,Step step) { Bukkit.getScheduler().runTaskLater(this,()-> {try {step.run();} catch(Throwable error) {fail(error);}},ticks); }
    private void check(boolean okay,String message) { if(!okay) throw new AssertionError(message); getLogger().info("PASS "+message); }
    private void finish() throws Exception { Files.writeString(marker,"PASS"); getLogger().info("PASS native logging interactions"); }
    private void fail(Throwable error) { getLogger().log(java.util.logging.Level.SEVERE,"FAIL logging probe",error); try{Files.writeString(marker,"FAIL "+error);}catch(Exception ignored){} }
    private interface Step { void run() throws Exception; }
}
