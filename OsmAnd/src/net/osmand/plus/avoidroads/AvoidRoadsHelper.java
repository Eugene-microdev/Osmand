package net.osmand.plus.avoidroads;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import net.osmand.IndexConstants;
import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.QuadRect;
import net.osmand.data.QuadTree;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.ApplicationMode;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AvoidRoadsHelper {

	private static final Log LOG = PlatformUtil.getLog(AvoidRoadsHelper.class);

	private static final int FROM_URL = 101;
	private static final int FROM_STORAGE = 100;
	private static final int SOURCE = FROM_STORAGE;


	private final Map<RouteDataObject, Location> roadsToAvoid;
	private final List<Location> parsedPoints;
	private final OsmandApplication app;
	private final ApplicationMode appMode;
	private CompletionCallback completionCallback;
	private ParsingProgressCallback ppc;
	private ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
	private WeakReference<MapActivity> mapActivityWeakReference = null;

	private static boolean saveResultToFile = false;

	private long timeStart;
	private long timeDownload;
	private long timeParsePoints;
	private long timeFindSegments;

	private final int[] pointsToProcess = {-1};
//	private String inputFileName = "points_500.json";
//	private String inputFileName = "point_100.json";
	private String inputFileName = "points_10.json";
//	private String testurl = "https://gist.githubusercontent.com/MadWasp79/1238d8878792572e343eb2e296c3c7f5/raw/494f872425993797c3a3bc79a4ec82039db6ee46/point_100.json";
	private String testurl = "https://gist.githubusercontent.com/MadWasp79/45f362ea48e9e8edd1593113593993c5/raw/6e817fb3bc7eaeaa3eda24847fde4855eb22485d/points_500.json";


	public AvoidRoadsHelper(final OsmandApplication app) {
		this.app = app;
		this.appMode = app.getSettings().getApplicationMode();
		this.roadsToAvoid = new LinkedHashMap<>();
		this.parsedPoints = new ArrayList<>();

		completionCallback = new CompletionCallback() {
			@Override
			public void onRDOSearchComplete() {
				timeFindSegments = System.currentTimeMillis();
				if (saveResultToFile) {
					File out = new File (app.getAppPath(IndexConstants.AVOID_ROADS_DIR).getAbsolutePath() + "/"
							+ "processed_ids.json");
					saveRoadsToJson(roadsToAvoid, out);
				}
				app.getAvoidRoadsHelper().addResultToImpassibleRoads(roadsToAvoid);

				MapActivity mapActivity = mapActivityWeakReference.get();
				if (mapActivity != null) {
					app.getAvoidRoadsHelper().showParsingCompleteDialog(mapActivity);
				} else {
					app.showToastMessage("Successfully processed roads to avoid. Applying result to routing parameters");
				}
			}

			@Override
			public void onPointsParsed(List<Location> result) {
				app.getRoutingConfig().clearImpassableRoads();
				parsedPoints.clear();
				parsedPoints.addAll(result);
				convertPointsToRDO(parsedPoints);
			}
		};
	}

	public Map<RouteDataObject, Location> getRoadsToAvoid() {
		return roadsToAvoid;
	}

	public void addResultToImpassibleRoads(Map<RouteDataObject, Location> result) {
		app.getRoutingConfig().addMultipleImpassableRoads(result);
	}

	public void progressDialog(final MapActivity activity) {
		AlertDialog.Builder adb = new AlertDialog.Builder(activity);
		adb.setMessage("Searching Roads to Avoid...");

		ppc = new ParsingProgressCallback() {
			@Override
			public void onParsingProgress(int percent) {

			}
		};
	}

	public void showUrlDialog(final MapActivity activity) {
		mapActivityWeakReference = new WeakReference<MapActivity>(activity);
		pointsToProcess[0] = -1;
		final AlertDialog.Builder db = new AlertDialog.Builder(activity);
		final View dialogView = activity.getLayoutInflater().inflate(R.layout.load_avoid_roads_dialog, null);
		final RadioGroup rg = (RadioGroup) dialogView.findViewById(R.id.point_quantity_selector);
		final EditText urlEt = (EditText) dialogView.findViewById(R.id.json_url_et);
		db.setTitle("Process Avoid Roads");
		db.setView(dialogView);
		db.setIcon(R.drawable.map_pin_avoid_road);
		db.setPositiveButton("Parse", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				boolean isValidJsonUrl = false;
				int selectedId = rg.getCheckedRadioButtonId();
				RadioButton rb = (RadioButton) dialogView.findViewById(selectedId);
				if (rb != null) {
					switch (rb.getText().toString()) {
						case "10":
							pointsToProcess[0] = 10;
							break;
						case "100":
							pointsToProcess[0] = 100;
							break;
						case "500":
							pointsToProcess[0] = 500;
					}
				}

				String urlFromEt = urlEt.getText().toString();
				if (Algorithms.isEmpty(urlFromEt)) {
					urlFromEt = testurl;
				}
				try {
					URL test = new URL(urlFromEt);
					if (urlFromEt.endsWith(".json")) {
						isValidJsonUrl = true;
					}
				} catch (MalformedURLException e){
					isValidJsonUrl  = false;
					app.showShortToastMessage("Enter valid JSON url!");
				}
				if (isValidJsonUrl) {
					timeStart = System.currentTimeMillis();
					app.showShortToastMessage("Downloading JSON");
					loadJson(urlFromEt, FROM_URL);
				}
			}
		});
		db.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				pointsToProcess[0] = -1;
				dialog.dismiss();
			}
		});
		db.show();
	}

	public void showParsingCompleteDialog(final MapActivity activity) {
		final AlertDialog.Builder db = new AlertDialog.Builder(activity);
		db.setTitle("Processing complete!");
		db.setMessage(String.format("Found %d unique roads to avoid.\n" +
				"Time to download: %.3fs\n" +
				"Time to parse JSON: %.3fs\n" +
				"Time to find roads: %.3fs\n",
				roadsToAvoid.size(),
				(timeDownload-timeStart)/1000.0f,
				(timeParsePoints-timeDownload)/1000.0f,
				(timeFindSegments-timeParsePoints)/1000.0f));
		db.setIcon(R.drawable.map_pin_avoid_road);
		db.setPositiveButton("OK", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		db.show();
	}

	public void testRun() {
		String in = "";
		switch (SOURCE) {
			case FROM_STORAGE:
				in = app.getAppPath(IndexConstants.AVOID_ROADS_DIR).getAbsolutePath()  + "/" + inputFileName;
				LOG.debug(String.format("Input json from file: %s", in));
				break;
			case FROM_URL:
				LOG.debug(String.format("Loading json from url: %s", in));
				break;
		}

		loadJson(in, SOURCE);
	}

	private void convertPointsToRDO(final List<Location> parsedPoints) {
		this.roadsToAvoid.clear();
		app.getLocationProvider().getMultipleRouteSegmentsIds(parsedPoints, appMode, false,
				new ResultMatcher<Map<RouteDataObject, Location>>() {

			@Override
			public boolean publish(Map<RouteDataObject, Location> result) {
				if (result == null || result.isEmpty()) {
					LOG.error("Error! No valid result");
				} else {
					roadsToAvoid.putAll(result);
					LOG.debug(String.format("Found %d road ids", result.size()));
					app.getAvoidRoadsHelper().completionCallback.onRDOSearchComplete();
				}
				return true;
			}
			@Override
			public boolean isCancelled() {
				return false;
			}
		});

	}

	@SuppressLint("StaticFieldLeak")
	public void loadJson(final String path, final int source) {
		new AsyncTask<Void, Void, List<Location>>() {
			@Override
			protected List<Location> doInBackground(Void... voids) {
				InputStream is = null;
				try {
					switch(source) {
						case FROM_STORAGE:
							is = new FileInputStream(path);
							break;
						case FROM_URL:
							URLConnection connection = NetworkUtils.getHttpURLConnection(path);
							is = connection.getInputStream();
							break;
					}

					List<Location> result = new ArrayList<>();
					timeDownload = System.currentTimeMillis();
					parseJson(is, result);
					if (is != null) {
						is.close();
					}
					return result;
				} catch (Exception e) {
					LOG.error("Error reading json!");
				} finally {
					if (is != null) {
						try {
							is.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
				return null;
			}

			@Override
			protected void onPostExecute(List<Location> result) {
				if (!Algorithms.isEmpty(result)) {
					app.getAvoidRoadsHelper().completionCallback.onPointsParsed(result);
				}
			}
		}.executeOnExecutor(singleThreadExecutor);
	}

	private void parseJson(InputStream is, List<Location> result) {
		Gson gson = new Gson();
		GeoJSON geoJSON = gson.fromJson(new BufferedReader(new InputStreamReader(is)), GeoJSON.class);
		double minlat = 0 , maxlat = 0, minlon = 0, maxlon= 0;
		boolean first = true;
		int limit = pointsToProcess[0];
		if (limit == -1 || limit > geoJSON.points.size()) {
			limit = geoJSON.points.size();
		}
		for (int i = 0; i < limit; i++) {
			Point o = geoJSON.points.get(i);
			Location ll = new Location("geoJSON");
			double lat = o.geo.coordinates.get(1);
			double lon = o.geo.coordinates.get(0);
			if(first) {
				minlat = maxlat = lat;
				minlon = maxlon = lon;
				first = false;
			} else {
				minlat = Math.min(minlat, lat);
				minlon = Math.min(minlon, lon);
				maxlat = Math.max(maxlat, lat);
				maxlon = Math.max(maxlon, lon);
			}
			ll.setLatitude(lat);
			ll.setLongitude(lon);
			result.add(ll);
		}
		QuadRect qr = new QuadRect(minlon, minlat, maxlon, maxlat);
		QuadTree<Location> qt = new QuadTree<Location>(qr, 8, 0.55f);
		for(Location l : result) {
			qt.insert(l, (float)l.getLongitude(), (float) l.getLatitude());
		}
		qt.queryInBox(qr, result);
		timeParsePoints = System.currentTimeMillis();
		app.showShortToastMessage(String.format("Loaded and parsed %d points from JSON. Starting segment search.", result.size()));
		LOG.debug(String.format("Points parsed: %d", result.size()));
	}

	@SuppressLint("StaticFieldLeak")
	private void saveRoadsToJson(final Map<RouteDataObject, Location> roadsToAvoid, final File out) {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... voids) {
				FileWriter fileWriter = null;
				if (out.exists()) {
					out.delete();
				}
				try {
					Gson gson = new Gson();
					fileWriter = new FileWriter(out, true);
					gson.toJson(new RoadsToAvoid (roadsToAvoid), fileWriter);
					fileWriter.close();
					LOG.info(String.format("File saved: %s ", out.getAbsolutePath()));
				} catch (Exception e) {
					//inform user about error
					LOG.error("Error writing file");
				} finally {
					if (fileWriter != null) {
						try {
							fileWriter.close();
						} catch (IOException e) {
						}
					}
				}
				return null;
			}
		}.executeOnExecutor(singleThreadExecutor);
	}

	interface CompletionCallback {
		void onRDOSearchComplete();
		void onPointsParsed(List<Location> result);
	}

	interface ParsingProgressCallback {
		void onParsingProgress(int percent);
	}

	class GeoJSON {
		@SerializedName("type")
		@Expose
		String type;
		@SerializedName("features")
		@Expose
		List<Point> points = null;

	}

	class Point {
		@SerializedName("type")
		@Expose
		String type;
		@SerializedName("geometry")
		@Expose
		Geometry geo;
	}

	class Geometry {
		@SerializedName("type")
		@Expose
		String type;
		@SerializedName("coordinates")
		@Expose
		List<Double> coordinates = null;

	}

	class RoadsToAvoid {
		@SerializedName("avoid_roads")
		@Expose
		List<RoadToAvoid> roadsToAvoid;

		public RoadsToAvoid(Map<RouteDataObject, Location> roads) {
			this.roadsToAvoid = new ArrayList<>();
			for (Map.Entry<RouteDataObject, Location> road : roads.entrySet()) {
				roadsToAvoid.add(new RoadToAvoid (road.getKey().id >> 6, road.getValue().getLatitude(), road.getValue().getLongitude()));
			}
		}
	}

	class RoadToAvoid {

		@SerializedName("road_id")
		@Expose
		long roadId;
		@SerializedName("lat")
		@Expose
		double lat;
		@SerializedName("lon")
		@Expose
		double lon;

		RoadToAvoid(long roadId, double lat, double lon) {
			this.roadId = roadId;
			this.lat = lat;
			this.lon = lon;
		}
	}

}
