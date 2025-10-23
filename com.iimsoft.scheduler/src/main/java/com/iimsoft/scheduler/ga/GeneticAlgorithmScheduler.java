package com.iimsoft.scheduler.ga;

import com.iimsoft.scheduler.domain.HourPlan;
import com.iimsoft.scheduler.domain.Item;
import com.iimsoft.scheduler.domain.Line;
import com.iimsoft.scheduler.domain.LineHourSlot;
import com.iimsoft.scheduler.domain.ProductionSchedule;
import org.optaplanner.core.api.score.ScoreManager;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * 遗传算法求解器：
 * - 基因 = 对每个 HourPlan 的 (itemIndex, quantity)
 * - 适应度 = DRL 评分（HardSoftScore），用 ScoreManager 评估
 * - 选择 = 锦标赛
 * - 交叉 = 均匀交叉（逐基因，两变量联动）
 * - 变异 = 概率随机替换 item 或 quantity
 */
public class GeneticAlgorithmScheduler {

    public static class GAParams {
        public int populationSize = 120;
        public int generations = 400;
        public double crossoverRate = 0.9;
        public double mutationRate = 0.03; // 单基因变异率
        public int tournamentSize = 4;
        public boolean parallelEvaluation = true;
        public long randomSeed = 0L; // 0 表示使用 ThreadLocalRandom
        public int eliteCount = 2;   // 精英保留
    }

    private final ScoreManager<ProductionSchedule, HardSoftScore> scoreManager;
    private final Random rnd;

    public GeneticAlgorithmScheduler(ScoreManager<ProductionSchedule, HardSoftScore> scoreManager, long seed) {
        this.scoreManager = Objects.requireNonNull(scoreManager);
        this.rnd = (seed == 0L) ? null : new Random(seed);
    }

    public GeneticAlgorithmScheduler(ScoreManager<ProductionSchedule, HardSoftScore> scoreManager) {
        this(scoreManager, 0L);
    }

    public ProductionSchedule solve(ProductionSchedule baseProblem, GAParams params) {
        Objects.requireNonNull(baseProblem, "baseProblem");
        Objects.requireNonNull(params, "params");

        // 固定事实
        List<Item> items = baseProblem.getItemList();
        List<Line> lines = baseProblem.getLineList();
        List<LineHourSlot> slots = baseProblem.getHourSlotList();
        List<HourPlan> plans = baseProblem.getPlanList();
        int nPlans = plans.size();

        // 物料索引映射
        Map<Item, Integer> itemToIdx = new HashMap<>(items.size() * 2);
        for (int i = 0; i < items.size(); i++) itemToIdx.put(items.get(i), i);

        // 针对每个 plan，预计算该产线允许的 item 索引集合（含 -1 表示空）
        int[][] allowedItemIdxPerPlan = new int[nPlans][];
        int[] maxQtyPerPlanPerItem = new int[nPlans * Math.max(1, items.size())]; // 展平: planIdx*items + itemIdx -> uph
        for (int p = 0; p < nPlans; p++) {
            Line line = plans.get(p).getLine();
            List<Integer> allowed = new ArrayList<>();
            allowed.add(-1); // 空
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (line.supportsItem(it)) {
                    allowed.add(i);
                    int uph = Math.max(0, line.getUnitsPerHourForItem(it));
                    maxQtyPerPlanPerItem[p * items.size() + i] = Math.min(uph, Math.max(1, baseProblem.getMaxQuantityPerHour()));
                } else {
                    maxQtyPerPlanPerItem[p * items.size() + i] = 0;
                }
            }
            allowedItemIdxPerPlan[p] = allowed.stream().mapToInt(Integer::intValue).toArray();
        }

        // 生成种群
        List<Genome> population = new ArrayList<>(params.populationSize);
        for (int k = 0; k < params.populationSize; k++) {
            Genome g = randomGenome(nPlans, allowedItemIdxPerPlan, maxQtyPerPlanPerItem, items.size(), baseProblem.getMaxQuantityPerHour());
            evaluateGenome(g, baseProblem, items);
            population.add(g);
        }
        population.sort(Comparator.comparing((Genome g) -> g.score)); // HardSoftScore implements Comparable (越大越好)

        // 演化
        int noImprove = 0;
        Genome globalBest = population.get(population.size() - 1).copy();
        for (int gen = 1; gen <= params.generations; gen++) {
            List<Genome> next = new ArrayList<>(params.populationSize);

            // 精英保留
            int elites = Math.min(params.eliteCount, population.size());
            for (int i = population.size() - elites; i < population.size(); i++) {
                next.add(population.get(i).copy());
            }

            // 产生后代
            while (next.size() < params.populationSize) {
                Genome p1 = tournamentSelect(population, params.tournamentSize);
                Genome p2 = tournamentSelect(population, params.tournamentSize);

                Genome c1 = p1.copy();
                Genome c2 = p2.copy();

                if (randomDouble() < params.crossoverRate) {
                    uniformCrossover(c1, c2);
                }
                mutate(c1, params.mutationRate, allowedItemIdxPerPlan, maxQtyPerPlanPerItem, items.size(), baseProblem.getMaxQuantityPerHour());
                mutate(c2, params.mutationRate, allowedItemIdxPerPlan, maxQtyPerPlanPerItem, items.size(), baseProblem.getMaxQuantityPerHour());

                next.add(c1);
                if (next.size() < params.populationSize) next.add(c2);
            }

            // 评估
            evaluatePopulation(next, baseProblem, items, params.parallelEvaluation);
            next.sort(Comparator.comparing((Genome g) -> g.score));

            Genome best = next.get(next.size() - 1);
            if (best.score.compareTo(globalBest.score) > 0) {
                globalBest = best.copy();
                noImprove = 0;
            } else {
                noImprove++;
            }

            population = next;
            // 可加早停，例如 noImprove 连续超过某阈值
        }

        // 回放最佳解为 ProductionSchedule
        return materialize(baseProblem, items, globalBest);
    }

    // ============ Genome ============

    private static class Genome {
        final int[] itemIdx; // -1 表示空
        final int[] qty;     // 0..max
        HardSoftScore score;

        Genome(int n) {
            this.itemIdx = new int[n];
            this.qty = new int[n];
        }
        Genome copy() {
            Genome g = new Genome(itemIdx.length);
            System.arraycopy(this.itemIdx, 0, g.itemIdx, 0, itemIdx.length);
            System.arraycopy(this.qty, 0, g.qty, 0, qty.length);
            g.score = this.score;
            return g;
        }
    }

    private Genome randomGenome(int nPlans,
                                int[][] allowedItemIdxPerPlan,
                                int[] maxQtyPerPlanPerItem,
                                int nItems,
                                int globalMax) {
        Genome g = new Genome(nPlans);
        for (int p = 0; p < nPlans; p++) {
            int[] allowed = allowedItemIdxPerPlan[p];
            int choice = allowed[randomInt(0, allowed.length)];
            g.itemIdx[p] = choice;
            if (choice < 0) {
                g.qty[p] = 0;
            } else {
                int cap = maxQtyPerPlanPerItem[p * nItems + choice];
                int maxQ = Math.min(Math.max(cap, 0), globalMax);
                g.qty[p] = (maxQ <= 0) ? 0 : randomInt(1, maxQ + 1);
            }
        }
        return g;
    }

    private void uniformCrossover(Genome a, Genome b) {
        int n = a.itemIdx.length;
        for (int i = 0; i < n; i++) {
            if (randomBoolean()) {
                int t = a.itemIdx[i]; a.itemIdx[i] = b.itemIdx[i]; b.itemIdx[i] = t;
                t = a.qty[i]; a.qty[i] = b.qty[i]; b.qty[i] = t;
            }
        }
    }

    private void mutate(Genome g,
                        double mutationRate,
                        int[][] allowedItemIdxPerPlan,
                        int[] maxQtyPerPlanPerItem,
                        int nItems,
                        int globalMax) {
        int n = g.itemIdx.length;
        for (int p = 0; p < n; p++) {
            if (randomDouble() < mutationRate) {
                int[] allowed = allowedItemIdxPerPlan[p];
                int newItem = allowed[randomInt(0, allowed.length)];
                g.itemIdx[p] = newItem;
                if (newItem < 0) {
                    g.qty[p] = 0;
                } else {
                    int cap = maxQtyPerPlanPerItem[p * nItems + newItem];
                    int maxQ = Math.min(Math.max(cap, 0), globalMax);
                    if (maxQ <= 0) {
                        g.itemIdx[p] = -1;
                        g.qty[p] = 0;
                    } else {
                        // 50% 概率微调数量，否则完全重抽
                        if (randomBoolean()) {
                            int delta = randomInt(-3, 4);
                            int q = Math.max(1, Math.min(maxQ, g.qty[p] + delta));
                            g.qty[p] = q;
                        } else {
                            g.qty[p] = randomInt(1, maxQ + 1);
                        }
                    }
                }
            }
        }
    }

    private Genome tournamentSelect(List<Genome> pop, int k) {
        int n = pop.size();
        Genome best = null;
        for (int i = 0; i < k; i++) {
            Genome g = pop.get(randomInt(0, n));
            if (best == null || g.score.compareTo(best.score) > 0) {
                best = g;
            }
        }
        return best;
    }

    private void evaluatePopulation(List<Genome> pop, ProductionSchedule base, List<Item> items, boolean parallel) {
        if (parallel) {
            pop.parallelStream().forEach(g -> evaluateGenome(g, base, items));
        } else {
            pop.forEach(g -> evaluateGenome(g, base, items));
        }
    }

    private void evaluateGenome(Genome g, ProductionSchedule base, List<Item> items) {
        ProductionSchedule sol = materialize(base, items, g);
        HardSoftScore s = scoreManager.updateScore(sol);
        g.score = s;
    }

    private ProductionSchedule materialize(ProductionSchedule base, List<Item> items, Genome g) {
        // 深拷贝固定事实引用（浅复制集合，事实对象复用；计划实体新建）
        ProductionSchedule s = new ProductionSchedule();
        s.setItemList(base.getItemList());
        s.setLineList(base.getLineList());
        s.setRouterList(base.getRouterList());
        s.setHourSlotList(base.getHourSlotList());
        s.setBomList(base.getBomList());
        s.setDemandList(base.getDemandList());
        s.setInventoryList(base.getInventoryList());
        s.setRequirementList(base.getRequirementList());
        s.setMaxQuantityPerHour(base.getMaxQuantityPerHour());

        List<HourPlan> plans = base.getPlanList();
        List<HourPlan> newPlans = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            HourPlan old = plans.get(i);
            HourPlan hp = new HourPlan(old.getId(), old.getSlot());
            int idx = g.itemIdx[i];
            hp.setItem(idx < 0 ? null : items.get(idx));
            hp.setQuantity(g.qty[i]);
            newPlans.add(hp);
        }
        s.setPlanList(newPlans);
        return s;
    }

    // ============ RNG helpers ============

    private int randomInt(int inclusive, int exclusive) {
        if (rnd != null) return inclusive + rnd.nextInt(exclusive - inclusive);
        return ThreadLocalRandom.current().nextInt(inclusive, exclusive);
    }
    private boolean randomBoolean() {
        if (rnd != null) return rnd.nextBoolean();
        return ThreadLocalRandom.current().nextBoolean();
    }
    private double randomDouble() {
        if (rnd != null) return rnd.nextDouble();
        return ThreadLocalRandom.current().nextDouble();
    }
}