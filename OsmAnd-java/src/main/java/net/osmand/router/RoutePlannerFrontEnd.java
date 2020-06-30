package net.osmand.router;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import net.osmand.NativeLibrary;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.data.QuadPoint;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegmentPoint;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import gnu.trove.list.array.TIntArrayList;

public class RoutePlannerFrontEnd {

	protected static final Log log = PlatformUtil.getLog(RoutePlannerFrontEnd.class);
	// Check issue #8649
	protected static final double GPS_POSSIBLE_ERROR = 7;
	public boolean useSmartRouteRecalculation = true;
	
	public static double AVERAGE_SPLIT_DISTANCE_GPX = 1500;
	public static double MINIMUM_POINT_APPROXIMATION = 50;
	public static double MINIMUM_STRAIGHT_STEP_APPROXIMATION = 50;

	
	public RoutePlannerFrontEnd() {
	}
	
	public enum RouteCalculationMode {
		BASE,
		NORMAL,
		COMPLEX
	}
	
	public static class GpxApproximationResult {
		public int routeCalculations = 0;
		public int routePointsSearched = 0;
		public int routeDistCalculations = 0;
		public List<RouteSegmentResult> res = new ArrayList<RouteSegmentResult>();
		public int routeDistance;
		public int routeDistanceUnmatched;
		
		@Override
		public String toString() {
			return String.format(">> GPX approximation (%d of %d m route calcs, %d route points searched) for %d m: %d m umatched",
					routeCalculations, routeDistCalculations, routePointsSearched, routeDistance, routeDistanceUnmatched);
		}

		public double distFromLastPoint(LatLon startPoint) {
			if(res.size() > 0) {
				return MapUtils.getDistance(getLastPoint(), startPoint);
			}
			return 0;
		}

		public LatLon getLastPoint() {
			if(res.size() > 0) {
				return res.get(res.size() - 1).getEndPoint();
			}
			return null;
		}
	}
	
	private static class GpxPoint {
		public int ind;
		public LatLon loc;
		public double cumDist;
		public RouteSegmentPoint pnt;
		public List<RouteSegmentResult> routeToTarget;
		public int targetInd = -1;
	}

	public RoutingContext buildRoutingContext(RoutingConfiguration config, NativeLibrary nativeLibrary, BinaryMapIndexReader[] map, RouteCalculationMode rm) {
		return new RoutingContext(config, nativeLibrary, map, rm);
	}

	public RoutingContext buildRoutingContext(RoutingConfiguration config, NativeLibrary nativeLibrary, BinaryMapIndexReader[] map) {
		return new RoutingContext(config, nativeLibrary, map, RouteCalculationMode.NORMAL);
	}


	private static double squareDist(int x1, int y1, int x2, int y2) {
		// translate into meters 
		double dy = MapUtils.convert31YToMeters(y1, y2, x1);
		double dx = MapUtils.convert31XToMeters(x1, x2, y1);
		return dx * dx + dy * dy;
	}

	public RouteSegmentPoint findRouteSegment(double lat, double lon, RoutingContext ctx, List<RouteSegmentPoint> list) throws IOException {
		return findRouteSegment(lat, lon, ctx, list, false);
	}

	public RouteSegmentPoint findRouteSegment(double lat, double lon, RoutingContext ctx, List<RouteSegmentPoint> list, boolean transportStop) throws IOException {
		int px = MapUtils.get31TileNumberX(lon);
		int py = MapUtils.get31TileNumberY(lat);
		ArrayList<RouteDataObject> dataObjects = new ArrayList<RouteDataObject>();
		ctx.loadTileData(px, py, 17, dataObjects);
		if (dataObjects.isEmpty()) {
			ctx.loadTileData(px, py, 15, dataObjects);
		}
		if (dataObjects.isEmpty()) {
			ctx.loadTileData(px, py, 14, dataObjects);
		}
		if (list == null) {
			list = new ArrayList<BinaryRoutePlanner.RouteSegmentPoint>();
		}
		for (RouteDataObject r : dataObjects) {
			if (r.getPointsLength() > 1) {
				RouteSegmentPoint road = null;
				for (int j = 1; j < r.getPointsLength(); j++) {
					QuadPoint pr = MapUtils.getProjectionPoint31(px, py, r.getPoint31XTile(j - 1),
							r.getPoint31YTile(j - 1), r.getPoint31XTile(j), r.getPoint31YTile(j));
					double currentsDistSquare = squareDist((int) pr.x, (int) pr.y, px, py);
					if (road == null || currentsDistSquare < road.distSquare) {
						RouteDataObject ro = new RouteDataObject(r);
						
						road = new RouteSegmentPoint(ro, j, currentsDistSquare);
						road.preciseX = (int) pr.x;
						road.preciseY = (int) pr.y;
					}
				}
				if (road != null) {
					if(!transportStop) {
						float prio = Math.max(ctx.getRouter().defineSpeedPriority(road.road), 0.3f);
						if (prio > 0) {
							road.distSquare = (road.distSquare + GPS_POSSIBLE_ERROR * GPS_POSSIBLE_ERROR)
									/ (prio * prio);
							list.add(road);
						}
					} else {
						list.add(road);
					}
					
				}
			}
		}
		Collections.sort(list, new Comparator<RouteSegmentPoint>() {

			@Override
			public int compare(RouteSegmentPoint o1, RouteSegmentPoint o2) {
				return Double.compare(o1.distSquare, o2.distSquare);
			}
		});
		if (list.size() > 0) {
			RouteSegmentPoint ps = null;
			if (ctx.publicTransport) {
				for (RouteSegmentPoint p : list) {
					if (transportStop && p.distSquare > GPS_POSSIBLE_ERROR * GPS_POSSIBLE_ERROR) {
						break;
					}
					boolean platform = p.road.platform();
					if (transportStop && platform) {
						ps = p;
						break;
					}
					if (!transportStop && !platform) {
						ps = p;
						break;
					}
				}
			}
			if (ps == null) {
				ps = list.get(0);
			}
			ps.others = list;
			return ps;
		}
		return null;
	}


	public List<RouteSegmentResult> searchRoute(final RoutingContext ctx, LatLon start, LatLon end, List<LatLon> intermediates) throws IOException, InterruptedException {
		return searchRoute(ctx, start, end, intermediates, null);
	}

	public void setUseFastRecalculation(boolean use) {
		useSmartRouteRecalculation = use;
	}


	// TODO add missing turns for straight lines
	// TODO smoothness is not correct for car routing
	// TODO native crash
	// TODO big gaps when there is no match
	// TODO not correct bicycle-> pedestrian
	// TODO slow - too many findRouteSegment
	// TODO fix progress / timings routing /
	// TODO smoothen straight line Douglas-Peucker (remove noise)
	public GpxApproximationResult searchGpxRoute(final RoutingContext ctx, List<LatLon> points) throws IOException, InterruptedException {
		GpxApproximationResult gctx = new GpxApproximationResult();
		List<GpxPoint> gpxPoints = generageGpxPoints(points, gctx);
		GpxPoint start = gpxPoints.size() > 0 ? gpxPoints.get(0) : null;
		
		while (start != null) {
			double routeDist = AVERAGE_SPLIT_DISTANCE_GPX;
			GpxPoint next = findNextGpxPointWithin(gctx, gpxPoints, start, routeDist);
			boolean routeFound = false;
			if (next != null && initRoutingPoint(start, gctx, ctx, MINIMUM_POINT_APPROXIMATION)) {
				boolean firstAttempt = true;
				while ((firstAttempt || next.cumDist - start.cumDist > MINIMUM_POINT_APPROXIMATION) && !routeFound) {
					firstAttempt = false;
					routeFound = initRoutingPoint(next, gctx, ctx, MINIMUM_POINT_APPROXIMATION);
					if (routeFound) {
						routeFound = findGpxRouteSegment(ctx, gctx, gpxPoints, start, next);
					}
					if (!routeFound) {
						// route is not found move next point closer to start point (distance / 2)
						routeDist = routeDist / 2;
						next = findNextGpxPointWithin(gctx, gpxPoints, start, routeDist);
					}
				}
			}
			if (routeFound) {
				// route is found, cut the end of the route and move to next iteration
				start = next;
			} else {
				// route is not found, move start point by 
				start = findNextGpxPointWithin(gctx, gpxPoints, start, MINIMUM_STRAIGHT_STEP_APPROXIMATION);
			}

		}
		calculateGpxRoute(ctx, gctx, gpxPoints);

		if (!gctx.res.isEmpty()) {
			new RouteResultPreparation().printResults(ctx, points.get(0), points.get(points.size() - 1), gctx.res);
			System.out.println(gctx);
		}
		return gctx;
	}

	private void calculateGpxRoute(final RoutingContext ctx, GpxApproximationResult gctx, List<GpxPoint> gpxPoints) {
		RouteRegion reg = new RouteRegion();
		reg.initRouteEncodingRule(0, "highway", "unmatched");
		TIntArrayList lastStraightLine = null;
		for (int i = 0; i < gpxPoints.size(); ) {
			GpxPoint pnt = gpxPoints.get(i);
			if (pnt.routeToTarget != null && !pnt.routeToTarget.isEmpty()) {
				LatLon startPoint = pnt.routeToTarget.get(0).getStartPoint();
				if (lastStraightLine != null) {
					lastStraightLine.add(MapUtils.get31TileNumberX(startPoint.getLongitude()));
					lastStraightLine.add(MapUtils.get31TileNumberY(startPoint.getLatitude()));
					addStraightLine(gctx.res, lastStraightLine, reg, gctx);
					lastStraightLine = null;
				}
				// TODO
				double distLastPnt = gctx.distFromLastPoint(pnt.routeToTarget.get(0).getStartPoint());
				double gpxDistPnt = gctx.distFromLastPoint(pnt.loc);
				if (distLastPnt > MINIMUM_POINT_APPROXIMATION / 5 || gpxDistPnt > MINIMUM_POINT_APPROXIMATION / 5) {
					System.out.println(String.format("????? routePnt - prevPnt = %f, gpxPoint - prevPnt = %f ",
							distLastPnt, gpxDistPnt));
				}
				gctx.res.addAll(pnt.routeToTarget);
				i = pnt.targetInd;
			} else {
				// add straight line from i -> i+1 
				if (lastStraightLine == null) {
					lastStraightLine = new TIntArrayList();
					// make smooth connection
					if(gctx.distFromLastPoint(pnt.loc) > 1) {
						lastStraightLine.add(MapUtils.get31TileNumberX(gctx.getLastPoint().getLongitude()));
						lastStraightLine.add(MapUtils.get31TileNumberY(gctx.getLastPoint().getLatitude()));
					}
				}
				lastStraightLine.add(MapUtils.get31TileNumberX(pnt.loc.getLongitude()));
				lastStraightLine.add(MapUtils.get31TileNumberY(pnt.loc.getLatitude()));
				i++;
			}
		}
		if (lastStraightLine != null) {
			addStraightLine(gctx.res, lastStraightLine, reg, gctx);
			lastStraightLine = null;
		}
		// clean turns to recaculate them
		cleanupResultAndAddTurns(ctx, gctx);
	}

	private List<GpxPoint> generageGpxPoints(List<LatLon> points, GpxApproximationResult gctx) {
		List<GpxPoint> gpxPoints = new ArrayList<>(points.size());
		GpxPoint prev = null;
		for(int i = 0; i < points.size(); i++) {
			GpxPoint p = new GpxPoint();
			p.ind = i;
			p.loc = points.get(i);
			if (prev != null) {
				p.cumDist = MapUtils.getDistance(p.loc, prev.loc) + prev.cumDist;
			}
			gpxPoints.add(p);
			gctx.routeDistance = (int) p.cumDist;
			prev = p;
		}
		return gpxPoints;
	}
 
	private void cleanupResultAndAddTurns(final RoutingContext ctx, GpxApproximationResult gctx) {
		// cleanup double joints
		int LOOK_AHEAD = 4;
		for(int i = 0; i < gctx.res.size(); i++) {
			RouteSegmentResult s = gctx.res.get(i);
			for(int j = i + 2; j <= i + LOOK_AHEAD && j < gctx.res.size(); j++) {
				RouteSegmentResult e = gctx.res.get(j);
				if(e.getStartPoint().equals(s.getEndPoint())) {
					while((--j) != i) {
						gctx.res.remove(j);
					}
					break;
				}
			}
		}
		RouteResultPreparation preparation = new RouteResultPreparation();
		for (RouteSegmentResult r : gctx.res) {
			r.setTurnType(null);
			r.setDescription("");
		}
		preparation.prepareTurnResults(ctx, gctx.res);
	}

	private void addStraightLine(List<RouteSegmentResult> res, TIntArrayList lastStraightLine, RouteRegion reg, GpxApproximationResult gctx) {
		RouteDataObject rdo = new RouteDataObject(reg);
		int l = lastStraightLine.size() / 2;
		rdo.pointsX = new int[l];
		rdo.pointsY = new int[l];
		rdo.types = new int[] { 0 } ;
		rdo.id = -1;
		for (int i = 0; i < l; i++) {
			rdo.pointsX[i] = lastStraightLine.get(i * 2);
			rdo.pointsY[i] = lastStraightLine.get(i * 2 + 1);
			if(i > 0) {
				double dist = MapUtils.squareRootDist31(rdo.pointsX[i], rdo.pointsY[i], rdo.pointsX[i-1], rdo.pointsY[i-1]);
				gctx.routeDistanceUnmatched += dist; 		
			}
		}
		res.add(new RouteSegmentResult(rdo, 0, rdo.getPointsLength() - 1));
	}
	
	private boolean initRoutingPoint(GpxPoint start, GpxApproximationResult gctx, RoutingContext ctx, double distThreshold) throws IOException {
		if (start != null && start.pnt == null) {
			gctx.routePointsSearched++;
			RouteSegmentPoint rsp = findRouteSegment(start.loc.getLatitude(), start.loc.getLongitude(), ctx, null, false);
			if (MapUtils.getDistance(rsp.getPreciseLatLon(), start.loc) < distThreshold) {
				start.pnt = rsp;
			}
 		}
		return start != null && start.pnt != null;
	}
	
	private GpxPoint findNextGpxPointWithin(GpxApproximationResult gctx, List<GpxPoint> gpxPoints,
			GpxPoint start, double dist) {
		int targetInd = start.ind + 1;
		GpxPoint target = null; 
		while (targetInd < gpxPoints.size()) {
			target = gpxPoints.get(targetInd);
			if (target.cumDist - start.cumDist > dist) {
				break;
			}
			targetInd++;
		}
		return target;
	}

	private boolean findGpxRouteSegment(final RoutingContext ctx, GpxApproximationResult gctx, List<GpxPoint> gpxPoints,
			GpxPoint start, GpxPoint target) throws IOException, InterruptedException {
		List<RouteSegmentResult> res = null;
		boolean routeIsCorrect = false;
		if (start.pnt != null && target.pnt != null) {
			gctx.routeDistCalculations += (target.cumDist - start.cumDist);
			gctx.routeCalculations++;
			res = searchRouteInternalPrepare(ctx, start.pnt, target.pnt, null);
			routeIsCorrect = res != null && !res.isEmpty();
			if (routeIsCorrect) {
				makeStartEndPointsPrecise(res, start.pnt.getPreciseLatLon(), target.pnt.getPreciseLatLon(), null);
			}
			for (int k = start.ind + 1; routeIsCorrect && k < target.ind; k++) {
				GpxPoint ipoint = gpxPoints.get(k);
				if (!pointCloseEnough(ipoint, res)) {
					routeIsCorrect = false;
				}
			}
			if (routeIsCorrect) {
				start.routeToTarget = res;
				start.targetInd = target.ind;
			}
		}
		return routeIsCorrect;
	}

	private boolean pointCloseEnough(GpxPoint ipoint, List<RouteSegmentResult> res) {
		int px = MapUtils.get31TileNumberX(ipoint.loc.getLongitude());
		int py = MapUtils.get31TileNumberY(ipoint.loc.getLatitude());
		double SQR = MINIMUM_POINT_APPROXIMATION * MINIMUM_POINT_APPROXIMATION * 4;
		for (RouteSegmentResult sr : res) {
			int start = sr.getStartPointIndex();
			int end = sr.getEndPointIndex();
			if (sr.getStartPointIndex() > sr.getEndPointIndex()) {
				start = sr.getEndPointIndex();
				end = sr.getStartPointIndex();
			}
			for (int i = start; i < end; i++) {
				RouteDataObject r = sr.getObject();
				QuadPoint pp = MapUtils.getProjectionPoint31(px, py, r.getPoint31XTile(i), r.getPoint31YTile(i),
						r.getPoint31XTile(i + 1), r.getPoint31YTile(i + 1));
				double currentsDist = squareDist((int) pp.x, (int) pp.y, px, py);
				if (currentsDist <= SQR) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean needRequestPrivateAccessRouting(RoutingContext ctx, List<LatLon> points) throws IOException {
		boolean res = false;
		GeneralRouter router = (GeneralRouter) ctx.getRouter();
		if (router != null && !router.isAllowPrivate() && 
				router.getParameters().containsKey(GeneralRouter.ALLOW_PRIVATE)) {
			ctx.unloadAllData();
			LinkedHashMap<String, String> mp = new LinkedHashMap<String, String>();
			mp.put(GeneralRouter.ALLOW_PRIVATE, "true");
			ctx.setRouter(new GeneralRouter(router.getProfile(), mp));
			for (LatLon latLon : points) {
				RouteSegmentPoint rp = findRouteSegment(latLon.getLatitude(), latLon.getLongitude(), ctx, null);
				if (rp != null && rp.road != null) {
					if (rp.road.hasPrivateAccess()) {
						res = true;
						break;
					}
				}
			}
			ctx.unloadAllData();
			ctx.setRouter(router);
		}
		return res;
	}

	public List<RouteSegmentResult> searchRoute(final RoutingContext ctx, LatLon start, LatLon end, List<LatLon> intermediates,
	                                            PrecalculatedRouteDirection routeDirection) throws IOException, InterruptedException {
		ctx.timeToCalculate = System.nanoTime();
		if (ctx.calculationProgress == null) {
			ctx.calculationProgress = new RouteCalculationProgress();
		}
		boolean intermediatesEmpty = intermediates == null || intermediates.isEmpty();
		List<LatLon> targets = new ArrayList<>();
		targets.add(end);
		if (!intermediatesEmpty) {
			targets.addAll(intermediates);
		}
		if (needRequestPrivateAccessRouting(ctx, targets)) {
			ctx.calculationProgress.requestPrivateAccessRouting = true;
		}
		double maxDistance = MapUtils.getDistance(start, end);
		if (!intermediatesEmpty) {
			LatLon b = start;
			for (LatLon l : intermediates) {
				maxDistance = Math.max(MapUtils.getDistance(b, l), maxDistance);
				b = l;
			}
		}
		if (ctx.calculationMode == RouteCalculationMode.COMPLEX && routeDirection == null
				&& maxDistance > ctx.config.DEVIATION_RADIUS * 6) {
			ctx.calculationProgress.totalIterations++;
			RoutingContext nctx = buildRoutingContext(ctx.config, ctx.nativeLib, ctx.getMaps(), RouteCalculationMode.BASE);
			nctx.calculationProgress = ctx.calculationProgress;
			List<RouteSegmentResult> ls = searchRoute(nctx, start, end, intermediates);
			if(ls == null) {
				return null;
			}
			routeDirection = PrecalculatedRouteDirection.build(ls, ctx.config.DEVIATION_RADIUS, ctx.getRouter().getMaxSpeed());
		}
		if (intermediatesEmpty && ctx.nativeLib != null) {
			ctx.startX = MapUtils.get31TileNumberX(start.getLongitude());
			ctx.startY = MapUtils.get31TileNumberY(start.getLatitude());
			ctx.targetX = MapUtils.get31TileNumberX(end.getLongitude());
			ctx.targetY = MapUtils.get31TileNumberY(end.getLatitude());
			RouteSegment recalculationEnd = getRecalculationEnd(ctx);
			if (recalculationEnd != null) {
				ctx.initTargetPoint(recalculationEnd);
			}
			if (routeDirection != null) {
				ctx.precalculatedRouteDirection = routeDirection.adopt(ctx);
			}
			ctx.calculationProgress.nextIteration();
			List<RouteSegmentResult> res = runNativeRouting(ctx, recalculationEnd);
			if (res != null) {
				new RouteResultPreparation().printResults(ctx, start, end, res);
			}
			makeStartEndPointsPrecise(res, start, end, intermediates);
			return res;
		}
		int indexNotFound = 0;
		List<RouteSegmentPoint> points = new ArrayList<RouteSegmentPoint>();
		if (!addSegment(start, ctx, indexNotFound++, points, ctx.startTransportStop)) {
			return null;
		}
		if (intermediates != null) {
			for (LatLon l : intermediates) {
				if (!addSegment(l, ctx, indexNotFound++, points, false)) {
					System.out.println(points.get(points.size() - 1).getRoad().toString());
					return null;
				}
			}
		}
		if (!addSegment(end, ctx, indexNotFound++, points, ctx.targetTransportStop)) {
			return null;
		}
		ctx.calculationProgress.nextIteration();
		List<RouteSegmentResult> res = searchRouteImpl(ctx, points, routeDirection);
		if (res != null) {
			new RouteResultPreparation().printResults(ctx, start, end, res);
		}
		return res;
	}

	protected void makeStartEndPointsPrecise(List<RouteSegmentResult> res, LatLon start, LatLon end, List<LatLon> intermediates) {
		if (res.size() > 0) {
			updateResult(res.get(0), start, true);
			updateResult(res.get(res.size() - 1), end, false);
		}
	}

	protected double projectDistance(List<RouteSegmentResult> res, int k, int px, int py) {
		RouteSegmentResult sr = res.get(k);
		RouteDataObject r = sr.getObject();
		QuadPoint pp = MapUtils.getProjectionPoint31(px, py,
				r.getPoint31XTile(sr.getStartPointIndex()), r.getPoint31YTile(sr.getStartPointIndex()),
				r.getPoint31XTile(sr.getEndPointIndex()), r.getPoint31YTile(sr.getEndPointIndex()));
		double currentsDist = squareDist((int) pp.x, (int) pp.y, px, py);
		return currentsDist;
	}

	private void updateResult(RouteSegmentResult routeSegmentResult, LatLon point, boolean st) {
		int px = MapUtils.get31TileNumberX(point.getLongitude());
		int py = MapUtils.get31TileNumberY(point.getLatitude());
		int pind = st ? routeSegmentResult.getStartPointIndex() : routeSegmentResult.getEndPointIndex();

		RouteDataObject r = new RouteDataObject(routeSegmentResult.getObject());
		routeSegmentResult.setObject(r);
		QuadPoint before = null;
		QuadPoint after = null;
		if (pind > 0) {
			before = MapUtils.getProjectionPoint31(px, py, r.getPoint31XTile(pind - 1),
					r.getPoint31YTile(pind - 1), r.getPoint31XTile(pind), r.getPoint31YTile(pind));
		}
		if (pind < r.getPointsLength() - 1) {
			after = MapUtils.getProjectionPoint31(px, py, r.getPoint31XTile(pind + 1),
					r.getPoint31YTile(pind + 1), r.getPoint31XTile(pind), r.getPoint31YTile(pind));
		}
		int insert = 0;
		double dd = MapUtils.getDistance(point, MapUtils.get31LatitudeY(r.getPoint31YTile(pind)),
				MapUtils.get31LongitudeX(r.getPoint31XTile(pind)));
		double ddBefore = Double.POSITIVE_INFINITY;
		double ddAfter = Double.POSITIVE_INFINITY;
		QuadPoint i = null;
		if (before != null) {
			ddBefore = MapUtils.getDistance(point, MapUtils.get31LatitudeY((int) before.y),
					MapUtils.get31LongitudeX((int) before.x));
			if (ddBefore < dd) {
				insert = -1;
				i = before;
			}
		}

		if (after != null) {
			ddAfter = MapUtils.getDistance(point, MapUtils.get31LatitudeY((int) after.y),
					MapUtils.get31LongitudeX((int) after.x));
			if (ddAfter < dd && ddAfter < ddBefore) {
				insert = 1;
				i = after;
			}
		}

		if (insert != 0) {
			if (st && routeSegmentResult.getStartPointIndex() < routeSegmentResult.getEndPointIndex()) {
				routeSegmentResult.setEndPointIndex(routeSegmentResult.getEndPointIndex() + 1);
			}
			if (!st && routeSegmentResult.getStartPointIndex() > routeSegmentResult.getEndPointIndex()) {
				routeSegmentResult.setStartPointIndex(routeSegmentResult.getStartPointIndex() + 1);
			}
			if (insert > 0) {
				r.insert(pind + 1, (int) i.x, (int) i.y);
				if (st) {
					routeSegmentResult.setStartPointIndex(routeSegmentResult.getStartPointIndex() + 1);
				}
				if (!st) {
					routeSegmentResult.setEndPointIndex(routeSegmentResult.getEndPointIndex() + 1);
				}
			} else {
				r.insert(pind, (int) i.x, (int) i.y);
			}

		}

	}

	private boolean addSegment(LatLon s, RoutingContext ctx, int indexNotFound, List<RouteSegmentPoint> res, boolean transportStop) throws IOException {
		RouteSegmentPoint f = findRouteSegment(s.getLatitude(), s.getLongitude(), ctx, null, transportStop);
		if (f == null) {
			ctx.calculationProgress.segmentNotFound = indexNotFound;
			return false;
		} else {
			log.info("Route segment found " + f.road);
			res.add(f);
			return true;
		}

	}

	private List<RouteSegmentResult> searchRouteInternalPrepare(final RoutingContext ctx, RouteSegmentPoint start, RouteSegmentPoint end,
	                                                            PrecalculatedRouteDirection routeDirection) throws IOException, InterruptedException {
		RouteSegment recalculationEnd = getRecalculationEnd(ctx);
		if (recalculationEnd != null) {
			ctx.initStartAndTargetPoints(start, recalculationEnd);
		} else {
			ctx.initStartAndTargetPoints(start, end);
		}
		if (routeDirection != null) {
			ctx.precalculatedRouteDirection = routeDirection.adopt(ctx);
		}
		if (ctx.nativeLib != null) {
			ctx.startX = start.preciseX;
			ctx.startY = start.preciseY;
			ctx.targetX = end.preciseX;
			ctx.targetY = end.preciseY;
			return runNativeRouting(ctx, recalculationEnd);
		} else {
			refreshProgressDistance(ctx);
			// Split into 2 methods to let GC work in between
			ctx.finalRouteSegment = new BinaryRoutePlanner().searchRouteInternal(ctx, start, end, recalculationEnd);
			// 4. Route is found : collect all segments and prepare result
			return new RouteResultPreparation().prepareResult(ctx, ctx.finalRouteSegment);
		}
	}

	public RouteSegment getRecalculationEnd(final RoutingContext ctx) {
		RouteSegment recalculationEnd = null;
		boolean runRecalculation = ctx.previouslyCalculatedRoute != null && ctx.previouslyCalculatedRoute.size() > 0
				&& ctx.config.recalculateDistance != 0;
		if (runRecalculation) {
			List<RouteSegmentResult> rlist = new ArrayList<RouteSegmentResult>();
			float distanceThreshold = ctx.config.recalculateDistance;
			float threshold = 0;
			for (RouteSegmentResult rr : ctx.previouslyCalculatedRoute) {
				threshold += rr.getDistance();
				if (threshold > distanceThreshold) {
					rlist.add(rr);
				}
			}
			runRecalculation = rlist.size() > 0;
			if (rlist.size() > 0) {
				RouteSegment previous = null;
				for (int i = 0; i <= rlist.size() - 1; i++) {
					RouteSegmentResult rr = rlist.get(i);
					RouteSegment segment = new RouteSegment(rr.getObject(), rr.getEndPointIndex());
					if (previous != null) {
						previous.setParentRoute(segment);
						previous.setParentSegmentEnd(rr.getStartPointIndex());
					} else {
						recalculationEnd = segment;
					}
					previous = segment;
				}
			}
		}
		return recalculationEnd;
	}


	private void refreshProgressDistance(RoutingContext ctx) {
		if (ctx.calculationProgress != null) {
			ctx.calculationProgress.distanceFromBegin = 0;
			ctx.calculationProgress.distanceFromEnd = 0;
			ctx.calculationProgress.reverseSegmentQueueSize = 0;
			ctx.calculationProgress.directSegmentQueueSize = 0;
			float rd = (float) MapUtils.squareRootDist31(ctx.startX, ctx.startY, ctx.targetX, ctx.targetY);
			float speed = 0.9f * ctx.config.router.getMaxSpeed();
			ctx.calculationProgress.totalEstimatedDistance = (float) (rd / speed);
		}

	}

	private List<RouteSegmentResult> runNativeRouting(final RoutingContext ctx, RouteSegment recalculationEnd) throws IOException {
		refreshProgressDistance(ctx);
		RouteRegion[] regions = ctx.reverseMap.keySet().toArray(new BinaryMapRouteReaderAdapter.RouteRegion[ctx.reverseMap.size()]);
		ctx.checkOldRoutingFiles(ctx.startX, ctx.startY);
		ctx.checkOldRoutingFiles(ctx.targetX, ctx.targetY);

		long time = System.currentTimeMillis();
		RouteSegmentResult[] res = ctx.nativeLib.runNativeRouting(ctx.startX, ctx.startY, ctx.targetX, ctx.targetY,
				ctx.config, regions, ctx.calculationProgress, ctx.precalculatedRouteDirection, ctx.calculationMode == RouteCalculationMode.BASE,
				ctx.publicTransport, ctx.startTransportStop, ctx.targetTransportStop);
		log.info("Native routing took " + (System.currentTimeMillis() - time) / 1000f + " seconds");
		ArrayList<RouteSegmentResult> result = new ArrayList<RouteSegmentResult>(Arrays.asList(res));
		if (recalculationEnd != null) {
			log.info("Native routing use precalculated route");
			RouteSegment current = recalculationEnd;
			while (current.getParentRoute() != null) {
				RouteSegment pr = current.getParentRoute();
				result.add(new RouteSegmentResult(pr.getRoad(), current.getParentSegmentEnd(), pr.getSegmentStart()));
				current = pr;
			}
		}
		ctx.routingTime = ctx.calculationProgress.routingCalculatedTime;
		ctx.visitedSegments = ctx.calculationProgress.visitedSegments;
		ctx.loadedTiles = ctx.calculationProgress.loadedTiles;
		return new RouteResultPreparation().prepareResult(ctx, result, recalculationEnd != null);
	}


	private List<RouteSegmentResult> searchRouteImpl(final RoutingContext ctx, List<RouteSegmentPoint> points, PrecalculatedRouteDirection routeDirection)
			throws IOException, InterruptedException {
		if (points.size() <= 2) {
			// simple case 2 points only
			if (!useSmartRouteRecalculation) {
				ctx.previouslyCalculatedRoute = null;
			}
			pringGC(ctx, true);
			List<RouteSegmentResult> res = searchRouteInternalPrepare(ctx, points.get(0), points.get(1), routeDirection);
			pringGC(ctx, false);
			makeStartEndPointsPrecise(res, points.get(0).getPreciseLatLon(), points.get(1).getPreciseLatLon(), null);
			return res;
		}

		ArrayList<RouteSegmentResult> firstPartRecalculatedRoute = null;
		ArrayList<RouteSegmentResult> restPartRecalculatedRoute = null;
		if (ctx.previouslyCalculatedRoute != null) {
			List<RouteSegmentResult> prev = ctx.previouslyCalculatedRoute;
			long id = points.get(1).getRoad().id;
			int ss = points.get(1).getSegmentStart();
			int px = points.get(1).getRoad().getPoint31XTile(ss);
			int py = points.get(1).getRoad().getPoint31YTile(ss);
			for (int i = 0; i < prev.size(); i++) {
				RouteSegmentResult rsr = prev.get(i);
				if (id == rsr.getObject().getId()) {
					if (MapUtils.getDistance(rsr.getPoint(rsr.getEndPointIndex()), MapUtils.get31LatitudeY(py),
							MapUtils.get31LongitudeX(px)) < 50) {
						firstPartRecalculatedRoute = new ArrayList<RouteSegmentResult>(i + 1);
						restPartRecalculatedRoute = new ArrayList<RouteSegmentResult>(prev.size() - i);
						for (int k = 0; k < prev.size(); k++) {
							if (k <= i) {
								firstPartRecalculatedRoute.add(prev.get(k));
							} else {
								restPartRecalculatedRoute.add(prev.get(k));
							}
						}
						System.out.println("Recalculate only first part of the route");
						break;
					}
				}
			}
		}
		List<RouteSegmentResult> results = new ArrayList<RouteSegmentResult>();
		for (int i = 0; i < points.size() - 1; i++) {
			RoutingContext local = new RoutingContext(ctx);
			if (i == 0) {
				if (useSmartRouteRecalculation) {
					local.previouslyCalculatedRoute = firstPartRecalculatedRoute;
				}
			}
			local.visitor = ctx.visitor;
			local.calculationProgress = ctx.calculationProgress;
			List<RouteSegmentResult> res = searchRouteInternalPrepare(local, points.get(i), points.get(i + 1), routeDirection);
			makeStartEndPointsPrecise(res, points.get(i).getPreciseLatLon(), points.get(i + 1).getPreciseLatLon(), null);
			results.addAll(res);
			ctx.distinctLoadedTiles += local.distinctLoadedTiles;
			ctx.loadedTiles += local.loadedTiles;
			ctx.visitedSegments += local.visitedSegments;
			ctx.loadedPrevUnloadedTiles += local.loadedPrevUnloadedTiles;
			ctx.timeToCalculate += local.timeToCalculate;
			ctx.timeToLoad += local.timeToLoad;
			ctx.timeToLoadHeaders += local.timeToLoadHeaders;
			ctx.relaxedSegments += local.relaxedSegments;
			ctx.routingTime += local.routingTime;

//			local.unloadAllData(ctx);
			if (restPartRecalculatedRoute != null) {
				results.addAll(restPartRecalculatedRoute);
				break;
			}
		}
		ctx.unloadAllData();
		return results;

	}

	private void pringGC(final RoutingContext ctx, boolean before) {
		if (RoutingContext.SHOW_GC_SIZE && before) {
			long h1 = RoutingContext.runGCUsedMemory();
			float mb = (1 << 20);
			log.warn("Used before routing " + h1 / mb + " actual");
		} else if (RoutingContext.SHOW_GC_SIZE && !before) {
			int sz = ctx.global.size;
			log.warn("Subregion size " + ctx.subregionTiles.size() + " " + " tiles " + ctx.indexedSubregions.size());
			RoutingContext.runGCUsedMemory();
			long h1 = RoutingContext.runGCUsedMemory();
			ctx.unloadAllData();
			RoutingContext.runGCUsedMemory();
			long h2 = RoutingContext.runGCUsedMemory();
			float mb = (1 << 20);
			log.warn("Unload context :  estimated " + sz / mb + " ?= " + (h1 - h2) / mb + " actual");
		}
	}

	

}
