package net.osmand.plus;

import android.app.Activity;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Html;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.IndexConstants;
import net.osmand.JsonUtils;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.map.ITileSource;
import net.osmand.map.TileSourceManager;
import net.osmand.map.WorldRegion;
import net.osmand.plus.SettingsHelper.AvoidRoadsSettingsItem;
import net.osmand.plus.SettingsHelper.MapSourcesSettingsItem;
import net.osmand.plus.SettingsHelper.PluginSettingsItem;
import net.osmand.plus.SettingsHelper.PoiUiFilterSettingsItem;
import net.osmand.plus.SettingsHelper.ProfileSettingsItem;
import net.osmand.plus.SettingsHelper.QuickActionsSettingsItem;
import net.osmand.plus.SettingsHelper.SettingsCollectListener;
import net.osmand.plus.SettingsHelper.SettingsItem;
import net.osmand.plus.download.DownloadActivityType;
import net.osmand.plus.download.DownloadIndexesThread;
import net.osmand.plus.download.DownloadResources;
import net.osmand.plus.download.IndexItem;
import net.osmand.plus.helpers.AvoidSpecificRoads;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.plus.quickaction.QuickAction;
import net.osmand.plus.quickaction.QuickActionRegistry;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static net.osmand.IndexConstants.SQLITE_EXT;

public class CustomOsmandPlugin extends OsmandPlugin {

	private static final Log LOG = PlatformUtil.getLog(CustomOsmandPlugin.class);

	private String pluginId;
	private String resourceDirName;
	private Map<String, String> names = new HashMap<>();
	private Map<String, String> descriptions = new HashMap<>();
	private Map<String, String> iconNames = new HashMap<>();
	private Map<String, String> imageNames = new HashMap<>();

	private Drawable icon;
	private Drawable image;

	private List<String> rendererNames = new ArrayList<>();
	private List<String> routerNames = new ArrayList<>();
	private List<SuggestedDownloadItem> suggestedDownloadItems = new ArrayList<>();
	private List<WorldRegion> customRegions = new ArrayList<>();

	public CustomOsmandPlugin(@NonNull OsmandApplication app, @NonNull JSONObject json) throws JSONException {
		super(app);
		pluginId = json.getString("pluginId");
		readAdditionalDataFromJson(json);
		readDependentFilesFromJson(json);
		loadResources();
	}

	@Override
	public String getId() {
		return pluginId;
	}

	@Override
	public String getName() {
		return JsonUtils.getLocalizedResFromMap(app, names, app.getString(R.string.custom_osmand_plugin));
	}

	@Override
	public CharSequence getDescription() {
		return Html.fromHtml(JsonUtils.getLocalizedResFromMap(app, descriptions, null));
	}

	public String getResourceDirName() {
		return resourceDirName;
	}

	@Override
	public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
		super.init(app, activity);
		if (activity != null) {
			// called from UI
			File pluginItemsFile = getPluginItemsFile();
			if (pluginItemsFile.exists()) {
				addPluginItemsFromFile(pluginItemsFile);
			}
		}
		return true;
	}

	@Override
	public void disable(OsmandApplication app) {
		super.disable(app);
		removePluginItems(null);
	}

	public File getPluginDir() {
		return app.getAppPath(IndexConstants.PLUGINS_DIR + pluginId);
	}

	public File getPluginItemsFile() {
		return new File(getPluginDir(), "items" + IndexConstants.OSMAND_SETTINGS_FILE_EXT);
	}

	public File getPluginResDir() {
		File pluginDir = getPluginDir();
		if (!Algorithms.isEmpty(resourceDirName)) {
			return new File(pluginDir, resourceDirName);
		}
		return pluginDir;
	}

	@Override
	public List<String> getRendererNames() {
		return rendererNames;
	}

	@Override
	public List<String> getRouterNames() {
		return routerNames;
	}

	private Drawable getIconForFile(String path, Map<String, String> fileNames) {
		for (Map.Entry<String, String> entry : fileNames.entrySet()) {
			String value = entry.getValue();
			if (value.startsWith("@")) {
				value = value.substring(1);
			}
			if (path.endsWith(value)) {
				return BitmapDrawable.createFromPath(path);
			}
		}
		return null;
	}

	@NonNull
	@Override
	public Drawable getLogoResource() {
		return icon != null ? icon : super.getLogoResource();
	}

	@Override
	public Drawable getAssetResourceImage() {
		return image;
	}

	@Override
	public List<WorldRegion> getDownloadMaps() {
		return customRegions;
	}

	@Override
	public List<IndexItem> getSuggestedMaps() {
		List<IndexItem> suggestedMaps = new ArrayList<>();

		DownloadIndexesThread downloadThread = app.getDownloadThread();
		if (!downloadThread.getIndexes().isDownloadedFromInternet && app.getSettings().isInternetConnectionAvailable()) {
			downloadThread.runReloadIndexFiles();
		}

		boolean downloadIndexes = app.getSettings().isInternetConnectionAvailable()
				&& !downloadThread.getIndexes().isDownloadedFromInternet
				&& !downloadThread.getIndexes().downloadFromInternetFailed;

		if (!downloadIndexes) {
			for (SuggestedDownloadItem item : suggestedDownloadItems) {
				DownloadActivityType type = DownloadActivityType.getIndexType(item.scopeId);
				if (type != null) {
					List<IndexItem> foundMaps = new ArrayList<>();
					String searchType = item.getSearchType();
					if ("latlon".equalsIgnoreCase(searchType)) {
						LatLon latLon = app.getMapViewTrackingUtilities().getMapLocation();
						foundMaps.addAll(getMapsForType(latLon, type));
					} else if ("worldregion".equalsIgnoreCase(searchType)) {
						LatLon latLon = app.getMapViewTrackingUtilities().getMapLocation();
						foundMaps.addAll(getMapsForType(latLon, type));
					}
					if (!Algorithms.isEmpty(item.getNames())) {
						foundMaps.addAll(getMapsForType(item.getNames(), type, item.getLimit()));
					}
					suggestedMaps.addAll(foundMaps);
				}
			}
		}

		return suggestedMaps;
	}

	public void setResourceDirName(String resourceDirName) {
		this.resourceDirName = resourceDirName;
	}

	private void addPluginItemsFromFile(final File file) {
		app.getSettingsHelper().collectSettings(file, "", 1, new SettingsCollectListener() {
			@Override
			public void onSettingsCollectFinished(boolean succeed, boolean empty, @NonNull List<SettingsItem> items) {
				if (succeed && !items.isEmpty()) {
					for (Iterator<SettingsItem> iterator = items.iterator(); iterator.hasNext(); ) {
						SettingsItem item = iterator.next();
						if (item instanceof ProfileSettingsItem) {
							ProfileSettingsItem profileSettingsItem = (ProfileSettingsItem) item;
							ApplicationMode mode = profileSettingsItem.getAppMode();
							ApplicationMode savedMode = ApplicationMode.valueOfStringKey(mode.getStringKey(), null);
							if (savedMode != null) {
								ApplicationMode.changeProfileAvailability(savedMode, true, app);
							}
							iterator.remove();
						} else if (!(item instanceof PluginSettingsItem)) {
							item.setShouldReplace(true);
						}
					}
					app.getSettingsHelper().importSettings(file, items, "", 1, null);
				}
			}
		});
	}

	public void removePluginItems(PluginItemsListener itemsListener) {
		File pluginItemsFile = getPluginItemsFile();
		if (pluginItemsFile.exists()) {
			removePluginItemsFromFile(pluginItemsFile, itemsListener);
		}
	}

	private void removePluginItemsFromFile(final File file, final PluginItemsListener itemsListener) {
		app.getSettingsHelper().collectSettings(file, "", 1, new SettingsCollectListener() {
			@Override
			public void onSettingsCollectFinished(boolean succeed, boolean empty, @NonNull List<SettingsItem> items) {
				if (succeed && !items.isEmpty()) {
					for (SettingsItem item : items) {
						if (item instanceof QuickActionsSettingsItem) {
							QuickActionsSettingsItem quickActionsSettingsItem = (QuickActionsSettingsItem) item;
							List<QuickAction> quickActions = quickActionsSettingsItem.getItems();
							QuickActionRegistry actionRegistry = app.getQuickActionRegistry();
							for (QuickAction action : quickActions) {
								QuickAction savedAction = actionRegistry.getQuickAction(app, action.getType(), action.getName(app), action.getParams());
								if (savedAction != null) {
									actionRegistry.deleteQuickAction(savedAction);
								}
							}
						} else if (item instanceof MapSourcesSettingsItem) {
							MapSourcesSettingsItem mapSourcesSettingsItem = (MapSourcesSettingsItem) item;
							List<ITileSource> mapSources = mapSourcesSettingsItem.getItems();

							for (ITileSource tileSource : mapSources) {
								if (tileSource instanceof TileSourceManager.TileSourceTemplate) {
									TileSourceManager.TileSourceTemplate sourceTemplate = (TileSourceManager.TileSourceTemplate) tileSource;
									File tPath = app.getAppPath(IndexConstants.TILES_INDEX_DIR);
									File dir = new File(tPath, sourceTemplate.getName());
									Algorithms.removeAllFiles(dir);
								} else if (tileSource instanceof SQLiteTileSource) {
									SQLiteTileSource sqLiteTileSource = ((SQLiteTileSource) tileSource);
									sqLiteTileSource.closeDB();

									File tPath = app.getAppPath(IndexConstants.TILES_INDEX_DIR);
									File dir = new File(tPath, sqLiteTileSource.getName() + SQLITE_EXT);
									Algorithms.removeAllFiles(dir);
								}
							}
						} else if (item instanceof PoiUiFilterSettingsItem) {
							PoiUiFilterSettingsItem poiUiFilterSettingsItem = (PoiUiFilterSettingsItem) item;
							List<PoiUIFilter> poiUIFilters = poiUiFilterSettingsItem.getItems();
							for (PoiUIFilter filter : poiUIFilters) {
								app.getPoiFilters().removePoiFilter(filter);
							}
							app.getPoiFilters().reloadAllPoiFilters();
							app.getPoiFilters().loadSelectedPoiFilters();
							app.getSearchUICore().refreshCustomPoiFilters();
						} else if (item instanceof AvoidRoadsSettingsItem) {
							AvoidRoadsSettingsItem avoidRoadsSettingsItem = (AvoidRoadsSettingsItem) item;
							List<AvoidSpecificRoads.AvoidRoadInfo> avoidRoadInfos = avoidRoadsSettingsItem.getItems();
							for (AvoidSpecificRoads.AvoidRoadInfo avoidRoad : avoidRoadInfos) {
								app.getAvoidSpecificRoads().removeImpassableRoad(avoidRoad);
							}
						} else if (item instanceof ProfileSettingsItem) {
							ProfileSettingsItem profileSettingsItem = (ProfileSettingsItem) item;
							ApplicationMode mode = profileSettingsItem.getAppMode();
							ApplicationMode savedMode = ApplicationMode.valueOfStringKey(mode.getStringKey(), null);
							if (savedMode != null) {
								ApplicationMode.changeProfileAvailability(savedMode, false, app);
							}
						}
					}
				}
				if (itemsListener != null) {
					itemsListener.onItemsRemoved();
				}
			}
		});
	}

	public void readAdditionalDataFromJson(JSONObject json) throws JSONException {
		iconNames = JsonUtils.getLocalizedMapFromJson("icon", json);
		imageNames = JsonUtils.getLocalizedMapFromJson("image", json);
		names = JsonUtils.getLocalizedMapFromJson("name", json);
		descriptions = JsonUtils.getLocalizedMapFromJson("description", json);

		JSONArray regionsJson = json.optJSONArray("regionsJson");
		if (regionsJson != null) {
			customRegions.addAll(collectRegionsFromJson(regionsJson));
		}
	}

	public void writeAdditionalDataToJson(JSONObject json) throws JSONException {
		JsonUtils.writeLocalizedMapToJson("icon", json, iconNames);
		JsonUtils.writeLocalizedMapToJson("image", json, imageNames);
		JsonUtils.writeLocalizedMapToJson("name", json, names);
		JsonUtils.writeLocalizedMapToJson("description", json, descriptions);

		JSONArray regionsJson = new JSONArray();
		for (WorldRegion region : getFlatCustomRegions()) {
			if (region instanceof CustomRegion) {
				regionsJson.put(((CustomRegion) region).toJson());
			}
		}
		json.put("regionsJson", regionsJson);
	}

	private List<WorldRegion> getFlatCustomRegions() {
		List<WorldRegion> l = new ArrayList<>(customRegions);
		for (WorldRegion region : customRegions) {
			collectCustomSubregionsFromRegion(region, l);
		}
		return l;
	}

	private void collectCustomSubregionsFromRegion(WorldRegion region, List<WorldRegion> items) {
		items.addAll(region.getSubregions());
		for (WorldRegion subregion : region.getSubregions()) {
			collectCustomSubregionsFromRegion(subregion, items);
		}
	}

	public void readDependentFilesFromJson(JSONObject json) throws JSONException {
		rendererNames = JsonUtils.jsonArrayToList("rendererNames", json);
		routerNames = JsonUtils.jsonArrayToList("routerNames", json);
		resourceDirName = json.optString("pluginResDir");
	}

	public void writeDependentFilesJson(JSONObject json) throws JSONException {
		JsonUtils.writeStringListToJson("rendererNames", json, rendererNames);
		JsonUtils.writeStringListToJson("routerNames", json, routerNames);

		json.put("pluginResDir", resourceDirName);
	}

	public static List<CustomRegion> collectRegionsFromJson(JSONArray jsonArray) throws JSONException {
		List<CustomRegion> customRegions = new ArrayList<>();
		Map<String, CustomRegion> flatRegions = new HashMap<>();
		for (int i = 0; i < jsonArray.length(); i++) {
			JSONObject regionJson = jsonArray.getJSONObject(i);
			CustomRegion region = CustomRegion.fromJson(regionJson);
			flatRegions.put(region.getPath(), region);
		}
		for (CustomRegion region : flatRegions.values()) {
			if (!Algorithms.isEmpty(region.getParentPath())) {
				CustomRegion parentReg = flatRegions.get(region.getParentPath());
				if (parentReg != null) {
					parentReg.addSubregion(region);
				}
			} else {
				customRegions.add(region);
			}
		}
		return customRegions;
	}

	public void addRouter(String fileName) {
		String routerName = Algorithms.getFileWithoutDirs(fileName);
		if (!routerNames.contains(routerName)) {
			routerNames.add(routerName);
		}
	}

	public void addRenderer(String fileName) {
		String rendererName = Algorithms.getFileWithoutDirs(fileName);
		if (!rendererNames.contains(rendererName)) {
			rendererNames.add(rendererName);
		}
	}

	public void loadResources() {
		File pluginResDir = getPluginResDir();
		if (pluginResDir.exists() && pluginResDir.isDirectory()) {
			File[] files = pluginResDir.listFiles();
			for (File resFile : files) {
				String path = resFile.getAbsolutePath();
				if (icon == null) {
					icon = getIconForFile(path, iconNames);
				}
				if (image == null) {
					image = getIconForFile(path, imageNames);
				}
			}
		}
	}

	public void updateSuggestedDownloads(List<SuggestedDownloadItem> items) {
		suggestedDownloadItems = new ArrayList<>(items);
	}

	public void updateDownloadItems(List<WorldRegion> items) {
		customRegions = new ArrayList<>(items);
	}

	private List<IndexItem> getMapsForType(LatLon latLon, DownloadActivityType type) {
		try {
			return DownloadResources.findIndexItemsAt(app, latLon, type);
		} catch (IOException e) {
			LOG.error(e);
		}
		return Collections.emptyList();
	}

	private List<IndexItem> getMapsForType(List<String> names, DownloadActivityType type, int limit) {
		return DownloadResources.findIndexItemsAt(app, names, type, false, limit);
	}

	public interface PluginItemsListener {

		void onItemsRemoved();

	}

	public static class SuggestedDownloadItem {

		private String scopeId;
		private String searchType;
		private List<String> names;
		private int limit;

		public SuggestedDownloadItem(String scopeId, String searchType, List<String> names, int limit) {
			this.scopeId = scopeId;
			this.limit = limit;
			this.searchType = searchType;
			this.names = names;
		}

		public String getScopeId() {
			return scopeId;
		}

		public String getSearchType() {
			return searchType;
		}

		public List<String> getNames() {
			return names;
		}

		public int getLimit() {
			return limit;
		}
	}
}