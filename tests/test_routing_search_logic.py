"""Python mirror of the Kotlin search in RoutingGraph.kt (A* with distance-limited small roads).

The Kotlin cannot be run here, so the same algorithm is replayed on the Python reader to validate the design:
the search must be exact, use small streets only near the ends, and widen its radius when nothing is found.
"""
import heapq
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_routing_graph as rg
from test_routing_graph import build, way

MAX_SPEED_MPS = 33.4
CLASS_RADIUS = [math.inf, math.inf, math.inf, 150_000.0, 40_000.0, 20_000.0, 15_000.0]
FACTORS = [1.0, 4.0, 1000.0]


def metres(reader, node, lat, lon):
    return rg.segment_metres(reader.lat[node], reader.lon[node], lat, lon)


def search(reader, start, goal, factor):
    goal_lat, goal_lon = reader.lat[goal], reader.lon[goal]
    start_lat, start_lon = reader.lat[start], reader.lon[start]
    best = {start: 0.0}
    closed = set()
    heap = [(0.0, start)]
    while heap:
        _, node = heapq.heappop(heap)
        if node in closed:
            continue
        closed.add(node)
        if node == goal:
            return best[goal], len(closed)
        for a in range(reader.arc_start[node], reader.arc_start[node + 1]):
            code = reader.arcs[a]
            edge = code >> 1
            target = reader.edge_to[edge] if code & 1 == 0 else reader.edge_from[edge]
            if target in closed:
                continue
            attr = reader.edge_attr[edge]
            road_class = attr & 7
            if road_class > 2:
                limit = CLASS_RADIUS[road_class] * factor
                if limit != math.inf and limit < 1e18:
                    if (metres(reader, target, start_lat, start_lon) > limit
                            and metres(reader, target, goal_lat, goal_lon) > limit):
                        continue
            speed = max(5, attr >> 3)
            cost = best[node] + reader.edge_length[edge] / 10.0 / (speed / 3.6)
            if target not in best or cost < best[target]:
                best[target] = cost
                heapq.heappush(heap, (cost + metres(reader, target, goal_lat, goal_lon) / MAX_SPEED_MPS, target))
    return None


def route(reader, start_lat, start_lon, end_lat, end_lon):
    start = reader.nearest_node(start_lat, start_lon)
    goal = reader.nearest_node(end_lat, end_lon)
    for factor in FACTORS:
        found = search(reader, start, goal, factor)
        if found:
            return found[0], factor, start, goal
    return None


def grid(origin_lon, origin_lat, size=6, step=0.002):
    ways = []
    for i in range(size):
        ways.append(way("residential", [[origin_lon + j * step, origin_lat + i * step] for j in range(size)]))
        ways.append(way("residential", [[origin_lon + i * step, origin_lat + j * step] for j in range(size)]))
    return ways


class SearchLogicTest(unittest.TestCase):
    def test_two_cities_joined_by_a_main_road(self):
        # city A (3.000..3.010), city B (3.300..3.310), 33 km of primary road between them
        roads = grid(3.000, 36.000) + grid(3.300, 36.000)
        roads.append(way("primary", [[3.010, 36.000], [3.150, 36.000], [3.300, 36.000]]))
        reader, *_ = build(roads)
        found = route(reader, 36.010, 3.000, 36.010, 3.310)
        self.assertIsNotNone(found)
        seconds, factor, start, goal = found
        self.assertEqual(factor, 1.0)
        # A* with an admissible heuristic must equal plain Dijkstra
        expected, _ = reader.shortest(start, goal)
        self.assertAlmostEqual(seconds, expected, places=3)
        self.assertGreater(seconds, 25 * 60 * 0.6)  # 30+ km cannot be done in less than ~15 minutes

    def test_search_stays_small_with_the_heuristic(self):
        roads = grid(3.000, 36.000) + grid(3.300, 36.000)
        roads.append(way("primary", [[3.010, 36.000], [3.300, 36.000]]))
        reader, *_ = build(roads)
        start = reader.nearest_node(36.010, 3.000)
        goal = reader.nearest_node(36.010, 3.310)
        _, expansions = search(reader, start, goal, 1.0)
        self.assertLessEqual(expansions, reader.nodes)

    def test_long_unclassified_access_road_needs_the_wider_radius(self):
        # a village linked by 40 km of unclassified road to a primary road leading to the destination
        roads = [
            way("unclassified", [[3.000, 36.000], [3.000, 36.360]]),
            way("primary", [[3.000, 36.360], [3.400, 36.360]]),
        ]
        reader, *_ = build(roads)
        start = reader.nearest_node(36.000, 3.000)
        goal = reader.nearest_node(36.360, 3.400)
        self.assertIsNone(search(reader, start, goal, 1.0))
        self.assertIsNotNone(search(reader, start, goal, 4.0))
        _, factor, _, _ = route(reader, 36.000, 3.000, 36.360, 3.400)
        self.assertEqual(factor, 4.0)

    def test_one_way_street_forces_a_detour(self):
        # square block: the direct side is one-way against the trip, so the route must go around
        roads = [
            way("residential", [[3.000, 36.000], [3.002, 36.000]], oneway="-1"),  # only B -> A allowed
            way("residential", [[3.000, 36.000], [3.000, 36.002], [3.002, 36.002], [3.002, 36.000]]),
        ]
        reader, *_ = build(roads)
        a = reader.nearest_node(36.000, 3.000)
        b = reader.nearest_node(36.000, 3.002)
        direct_back = reader.shortest(b, a)
        around = reader.shortest(a, b)
        self.assertLess(direct_back[0], around[0])  # B -> A uses the short one-way side
        self.assertNotEqual(around[1][0][0], direct_back[1][0][0])  # A -> B cannot use the one-way side
        self.assertIsNotNone(search(reader, a, b, 1.0))

    def test_unreachable_goal_gives_nothing(self):
        roads = [way("primary", [[3.0, 36.0], [3.1, 36.0]]), way("primary", [[4.0, 36.0], [4.1, 36.0]])]
        reader, *_ = build(roads)
        a = reader.nearest_node(36.0, 3.0, main_only=False)
        b = reader.nearest_node(36.0, 4.1, main_only=False)
        for factor in FACTORS:
            self.assertIsNone(search(reader, a, b, factor))


if __name__ == "__main__":
    unittest.main()
