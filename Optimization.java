package client.module.client;

import client.module.Category;
import client.module.Module;
import client.setting.BooleanSetting;
import client.setting.ListSetting;
import client.setting.Setting;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.ChunkBuilderMode;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.util.math.Vec3d;
import ru.pulse.performance.PerformanceProfiles;

/**
 * Optimization — объединённый оптимизатор (Pulse + Nuclear Optimizer).
 *
 * <p>Каждый тик вычисляется итоговое состояние настроек из снимка исходных
 * значений и включённых опций, а в игру записываются только отличающиеся
 * значения. Так опции не конфликтуют друг с другом и корректно откатываются
 * при выключении.
 */
public class Optimization extends Module {
   private static final String LOW = "Низкий";
   private static final String MEDIUM = "Средний";
   private static final String ULTRA = "Ультра";

   private static final int[] RENDER_DISTANCE_CAP = new int[]{16, 12, 8};
   private static final double[] PARTICLE_DISTANCE_MULT = new double[]{1.0, 0.8, 0.6};
   private static final double[] ENTITY_DISTANCE_SQ = new double[]{2304.0, 1024.0, 484.0};
   private static final double[] CULL_DISTANCE_SQ = new double[]{2304.0, 1296.0, 576.0};

   private static final int ENERGY_VIEW_DISTANCE = 8;
   private static final int ENERGY_SIMULATION_DISTANCE = 6;
   private static final double ENERGY_ENTITY_DISTANCE_SCALE = 0.5;
   // 260 в слайдере ванилы = «Без ограничений».
   private static final int MAX_FPS_TARGET = 260;

   private static final long GC_MIN_INTERVAL_MS = 120_000L;
   private static final double GC_HEAP_THRESHOLD = 0.85;

   private static volatile Optimization instance;

   private final ListSetting mode;
   private final BooleanSetting limitRenderDistance;
   private final BooleanSetting optimizeParticles;
   private final BooleanSetting threadedChunks;
   private final BooleanSetting freeMemory;
   private final BooleanSetting lowGraphics;
   private final BooleanSetting maxFps;
   private final BooleanSetting saveEnergy;

   private final PerformanceProfiles performance = new PerformanceProfiles();

   private GameSnapshot snapshot;
   private long lastMemoryCleanup;

   public Optimization() {
      super("Optimization", Category.CLIENT);

      this.mode = new ListSetting("", "", Arrays.asList(LOW, MEDIUM, ULTRA), List.of(MEDIUM), false);
      this.mode.setName("Режим");
      this.mode.setDescription("Насколько агрессивно ограничивать графику.");

      this.limitRenderDistance = bool(true, "Ограничивать прорисовку", "Ограничивает дистанцию прорисовки по режиму.");
      this.optimizeParticles = bool(true, "Оптимизировать частицы", "Уменьшает количество частиц для FPS.");
      this.threadedChunks = bool(true, "Многопоточные чанки",
         "Сборка чанков полностью в фоновых потоках на всех ядрах, без подгрузки в главном потоке. Убирает фризы при движении.");
      this.freeMemory = bool(true, "Free memory", "Чистит память, только когда она почти заполнена (не чаще раза в 2 минуты).");
      this.lowGraphics = bool(false, "Low graphics", "Быстрая графика, без облаков, мягкого освещения и сглаживания биомов.");
      this.maxFps = bool(false, "Max FPS", "Отключает vsync, снимает лимит FPS и убирает покачивание камеры.");
      this.saveEnergy = bool(false, "Экономить энергию", "Снижает частицы, тени, дистанции прорисовки и симуляции.");

      this.addSettings(new Setting[]{
         this.mode, this.limitRenderDistance, this.optimizeParticles, this.threadedChunks,
         this.freeMemory, this.lowGraphics, this.maxFps, this.saveEnergy
      });

      instance = this;
   }

   private static BooleanSetting bool(boolean def, String name, String description) {
      BooleanSetting setting = new BooleanSetting("", "", def);
      setting.setName(name);
      setting.setDescription(description);
      return setting;
   }

   @Override
   public void onEnable() {
      this.lastMemoryCleanup = System.currentTimeMillis();
      this.snapshot = GameSnapshot.capture(this.options());
      this.apply();
   }

   @Override
   public void onDisable() {
      this.performance.disable(this.options());
      if (this.snapshot != null) {
         this.snapshot.restore(this.options());
         this.snapshot = null;
      }
   }

   @Override
   protected void onSettingChanged(Setting setting) {
      if (this.isEnabled()) {
         this.apply();
      }
   }

   @Override
   public void onTick() {
      // Переприменяем каждый тик, чтобы игра не откатила настройки (смена мира и т.п.).
      // Запись идёт только при реальном отличии, поэтому это дёшево.
      if (this.isEnabled()) {
         this.apply();
      }
   }

   private void apply() {
      if (this.client().world == null) {
         return;
      }

      GameOptions options = this.options();
      if (this.snapshot == null) {
         this.snapshot = GameSnapshot.capture(options);
         if (this.snapshot == null) {
            return;
         }
      }

      GameSnapshot base = this.snapshot;
      int level = this.modeIndex();
      boolean energy = this.saveEnergy.isFlag3();
      boolean low = this.lowGraphics.isFlag3() || energy;
      boolean unlockFps = this.maxFps.isFlag3();

      int viewDistance = base.viewDistance;
      if (this.limitRenderDistance.isFlag3()) {
         viewDistance = Math.min(viewDistance, RENDER_DISTANCE_CAP[level]);
      }
      if (energy) {
         viewDistance = Math.min(viewDistance, ENERGY_VIEW_DISTANCE);
      }

      ParticlesMode particles = base.particles;
      if (energy) {
         particles = ParticlesMode.MINIMAL;
      } else if (this.optimizeParticles.isFlag3() && level >= 1) {
         particles = level >= 2 ? ParticlesMode.MINIMAL : ParticlesMode.DECREASED;
      }

      set(options.getViewDistance(), viewDistance);
      set(options.getSimulationDistance(), energy ? Math.min(base.simulationDistance, ENERGY_SIMULATION_DISTANCE) : base.simulationDistance);
      set(options.getEntityDistanceScaling(), energy ? Math.min(base.entityDistance, ENERGY_ENTITY_DISTANCE_SCALE) : base.entityDistance);
      set(options.getParticles(), particles);
      set(options.getEntityShadows(), energy ? Boolean.FALSE : base.entityShadows);

      set(options.getGraphicsMode(), low ? GraphicsMode.FAST : base.graphicsMode);
      set(options.getCloudRenderMode(), low ? CloudRenderMode.OFF : base.cloudMode);
      set(options.getBiomeBlendRadius(), low ? 0 : base.biomeBlend);
      set(options.getAo(), low ? Boolean.FALSE : base.ao);

      set(options.getEnableVsync(), unlockFps ? Boolean.FALSE : base.vsync);
      set(options.getMaxFps(), unlockFps ? MAX_FPS_TARGET : base.maxFps);
      set(options.getBobView(), unlockFps || energy ? Boolean.FALSE : base.bobView);

      set(options.getChunkBuilderMode(), this.threadedChunks.isFlag3() ? ChunkBuilderMode.NONE : base.chunkBuilderMode);

      this.performance.setMode(options, this.profileMode());
      this.cleanupMemory();
   }

   /** Пишет значение только при отличии: каждый setValue может запускать перестройку мира. */
   private static <T> void set(SimpleOption<T> option, T value) {
      try {
         if (!Objects.equals(option.getValue(), value)) {
            option.setValue(value);
         }
      } catch (Throwable ignored) {
      }
   }

   private void cleanupMemory() {
      if (!this.freeMemory.isFlag3()) {
         return;
      }
      long now = System.currentTimeMillis();
      if (now - this.lastMemoryCleanup < GC_MIN_INTERVAL_MS) {
         return;
      }
      this.lastMemoryCleanup = now;

      // System.gc() останавливает игру, поэтому вызываем его только при реальной нехватке памяти.
      Runtime runtime = Runtime.getRuntime();
      double used = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
      if (used >= GC_HEAP_THRESHOLD) {
         System.gc();
      }
   }

   private PerformanceProfiles.Mode profileMode() {
      int level = this.modeIndex();
      if (level >= 2) {
         return PerformanceProfiles.Mode.PERFORMANCE;
      }
      return level >= 1 ? PerformanceProfiles.Mode.BALANCED : PerformanceProfiles.Mode.OFF;
   }

   private int modeIndex() {
      if (this.mode.isString(ULTRA)) {
         return 2;
      }
      return this.mode.isString(MEDIUM) ? 1 : 0;
   }

   public static boolean isEnabledStatic() {
      Optimization opt = instance;
      return opt != null && opt.isEnabled();
   }

   public boolean isEnergyActive() {
      return this.isEnabled() && this.saveEnergy.isFlag3();
   }

   public static boolean isAggressive() {
      return isEnabledStatic() && instance.modeIndex() == 2;
   }

   public static boolean isMediumPlus() {
      return isEnabledStatic() && instance.modeIndex() >= 1;
   }

   public static int scaleCount(int count) {
      if (count <= 0 || !isEnabledStatic()) {
         return count;
      }

      return Math.max(1, switch (instance.modeIndex()) {
         case 0 -> count * 3 / 4;
         case 1 -> count / 2;
         default -> count / 4;
      });
   }

   public static boolean withinCullDistance(double x, double y, double z) {
      if (!isEnabledStatic()) {
         return true;
      }

      MinecraftClient client = MinecraftClient.getInstance();
      if (client.gameRenderer == null) {
         return true;
      }

      Vec3d camera = client.gameRenderer.getCamera().getPos();
      double dx = x - camera.x;
      double dy = y - camera.y;
      double dz = z - camera.z;
      return dx * dx + dy * dy + dz * dz <= ENTITY_DISTANCE_SQ[instance.modeIndex()];
   }

   public static boolean withinDetailDistance(double distanceSquared) {
      return !isEnabledStatic() || distanceSquared <= CULL_DISTANCE_SQ[instance.modeIndex()];
   }

   public static double scaleParticleDistance(double distance) {
      return isEnabledStatic() ? distance * PARTICLE_DISTANCE_MULT[instance.modeIndex()] : distance;
   }

   public static double scaleDetailDistance(double distance) {
      return isEnabledStatic() ? Math.min(distance, ENTITY_DISTANCE_SQ[instance.modeIndex()]) : distance;
   }

   /** Снимок исходных настроек игрока: от него считаются все ограничения и к нему идёт откат. */
   private static final class GameSnapshot {
      private final CloudRenderMode cloudMode;
      private final GraphicsMode graphicsMode;
      private final int biomeBlend;
      private final boolean ao;
      private final boolean vsync;
      private final int maxFps;
      private final boolean bobView;
      private final ParticlesMode particles;
      private final boolean entityShadows;
      private final int viewDistance;
      private final int simulationDistance;
      private final double entityDistance;
      private final ChunkBuilderMode chunkBuilderMode;

      private GameSnapshot(GameOptions o) {
         this.cloudMode = o.getCloudRenderMode().getValue();
         this.graphicsMode = o.getGraphicsMode().getValue();
         this.biomeBlend = o.getBiomeBlendRadius().getValue();
         this.ao = o.getAo().getValue();
         this.vsync = o.getEnableVsync().getValue();
         this.maxFps = o.getMaxFps().getValue();
         this.bobView = o.getBobView().getValue();
         this.particles = o.getParticles().getValue();
         this.entityShadows = o.getEntityShadows().getValue();
         this.viewDistance = o.getViewDistance().getValue();
         this.simulationDistance = o.getSimulationDistance().getValue();
         this.entityDistance = o.getEntityDistanceScaling().getValue();
         this.chunkBuilderMode = o.getChunkBuilderMode().getValue();
      }

      private static GameSnapshot capture(GameOptions options) {
         try {
            return new GameSnapshot(options);
         } catch (Throwable throwable) {
            return null;
         }
      }

      private void restore(GameOptions o) {
         set(o.getCloudRenderMode(), this.cloudMode);
         set(o.getGraphicsMode(), this.graphicsMode);
         set(o.getBiomeBlendRadius(), this.biomeBlend);
         set(o.getAo(), this.ao);
         set(o.getEnableVsync(), this.vsync);
         set(o.getMaxFps(), this.maxFps);
         set(o.getBobView(), this.bobView);
         set(o.getParticles(), this.particles);
         set(o.getEntityShadows(), this.entityShadows);
         set(o.getViewDistance(), this.viewDistance);
         set(o.getSimulationDistance(), this.simulationDistance);
         set(o.getEntityDistanceScaling(), this.entityDistance);
         set(o.getChunkBuilderMode(), this.chunkBuilderMode);
      }
   }
}
