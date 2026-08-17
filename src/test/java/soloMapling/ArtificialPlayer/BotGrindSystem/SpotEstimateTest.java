package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Headless tests for the WZ-position cluster estimator behind SpotFinder.mapBotCapacity — the
// carrying-capacity number DECIDE uses must track how many spots bots can actually claim.
class SpotEstimateTest {

    private static List<MapMobIndex.SpawnPos> row(int y, int fromX, int toX, int step) {
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int x = fromX; x <= toX; x += step) {
            pts.add(new MapMobIndex.SpawnPos(x, y, -1));
        }
        return pts;
    }

    @Test
    void emptyPositionsEstimateZero() {
        assertEquals(0, SpotFinder.estimateSpotCount(List.of()));
        assertEquals(0, SpotFinder.estimateSpotCount(null));
    }

    @Test
    void oneTightClusterIsOneSpot() {
        assertEquals(1, SpotFinder.estimateSpotCount(row(100, 0, 300, 30)));
    }

    @Test
    void distantClustersCountSeparately() {
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pts.add(new MapMobIndex.SpawnPos(i * 40, 0, -1));
            pts.add(new MapMobIndex.SpawnPos(2000 + i * 40, 0, -1));
        }
        assertEquals(2, SpotFinder.estimateSpotCount(pts));
    }

    @Test
    void verticallyStackedGroupsSplit() {
        // dy is weighted x2.5 by the merge metric, so 200px of stack reads as 500 > the 350 merge range
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pts.add(new MapMobIndex.SpawnPos(i * 50, 0, -1));
            pts.add(new MapMobIndex.SpawnPos(i * 50, 200, -1));
        }
        assertEquals(2, SpotFinder.estimateSpotCount(pts));
    }

    @Test
    void wideChainedRowTilesIntoMultipleSpots() {
        // one long chained cluster (gaps < merge px) spanning 3000px tiles into 1000px slices
        assertEquals(3, SpotFinder.estimateSpotCount(row(-50, 0, 3000, 200)));
    }

    @Test
    void shareCapacityScalesWithWidthAndNeverDropsBelowSpotCount() {
        List<MapMobIndex.SpawnPos> tight = row(100, 0, 300, 30);
        assertTrue(SpotFinder.estimateShareCapacity(tight) >= SpotFinder.estimateSpotCount(tight));

        List<MapMobIndex.SpawnPos> wide = row(-50, 0, 3000, 200);
        int wideSpots = SpotFinder.estimateSpotCount(wide);
        int wideCap = SpotFinder.estimateShareCapacity(wide);
        assertTrue(wideCap > wideSpots);
        assertTrue(wideCap <= wideSpots * 4); // bounded by SHARE_CAP_MAX per spot
    }

    // Monkey Swamp III (107000403), the map that exposed the swallowed-ledge bug: the real 44 WZ spawn
    // points, each tagged with its WZ foothold group. The anisotropic merge chains two stacked-platform
    // pairs (groups 33+34 and 26+29 — the latter seven pixels inside the threshold), and without the
    // per-ledge split each pair produced ONE spot: two 6-spawn platforms were never claimable at all,
    // plus a junk 1-spawn spot appeared from x-tiling a two-ledge cluster. {x, cy, fhGroup} per spawn.
    private static final int[][] MONKEY_SWAMP_3 = {
            // bottom floor chain: groups 12 (1 stray, folds), 10 (8), 11 (4)
            {-1003, 125, 12},
            {-918, 120, 10}, {-900, 119, 10}, {-812, 119, 10}, {-777, 121, 10},
            {-651, 121, 10}, {-645, 122, 10}, {-563, 121, 10}, {-486, 121, 10},
            {-403, 111, 11}, {-227, 92, 11}, {-98, 94, 11}, {164, 127, 11},
            // lone mid platform: group 22 (6)
            {-989, -242, 22}, {-922, -240, 22}, {-822, -238, 22},
            {-759, -235, 22}, {-604, -237, 22}, {-595, -238, 22},
            // stacked pair that merges into one cluster: groups 34 (6) + 33 (6)
            {-972, -839, 34}, {-867, -837, 34}, {-806, -840, 34},
            {-698, -838, 34}, {-616, -796, 34}, {-531, -781, 34},
            {-332, -717, 33}, {-243, -718, 33}, {-182, -721, 33},
            {-122, -717, 33}, {-62, -717, 33}, {47, -721, 33},
            // stacked pair that merges into one cluster: groups 29 (6) + 26 (7)
            {-986, -540, 29}, {-920, -540, 29}, {-867, -537, 29},
            {-787, -538, 29}, {-691, -537, 29}, {-555, -538, 29},
            {-371, -422, 26}, {-324, -420, 26}, {-243, -417, 26},
            {-124, -418, 26}, {-46, -416, 26}, {-31, -417, 26}, {75, -418, 26},
    };

    private static List<MapMobIndex.SpawnPos> monkeySwamp(boolean withLedges) {
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int[] p : MONKEY_SWAMP_3) {
            pts.add(new MapMobIndex.SpawnPos(p[0], p[1], withLedges ? p[2] : -1));
        }
        return pts;
    }

    @Test
    void monkeySwampWithoutLedgeDataReproducesTheOldMiss() {
        // ledge-blind = the pre-P6 behaviour: 5 spots, two spawn-heavy platforms invisible
        assertEquals(5, SpotFinder.estimateSpotCount(monkeySwamp(false)));
    }

    @Test
    void monkeySwampLedgeSplitSurfacesTheSwallowedPlatforms() {
        // per-ledge split: bottom floor 2 (stray folds into the 8-spawn run), lone platform 1,
        // both stacked pairs 2 each — and the junk 1-spawn tile spot is gone (it rejoins group 26)
        assertEquals(7, SpotFinder.estimateSpotCount(monkeySwamp(true)));
    }
}
