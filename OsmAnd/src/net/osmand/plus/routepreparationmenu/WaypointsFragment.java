package net.osmand.plus.routepreparationmenu;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.github.ksoichiro.android.observablescrollview.ObservableScrollViewCallbacks;

import net.osmand.StateChangedListener;
import net.osmand.data.FavouritePoint;
import net.osmand.data.LocationPoint;
import net.osmand.data.PointDescription;
import net.osmand.plus.GeocodingLookupService.AddressLookupRequest;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.BaseOsmAndFragment;
import net.osmand.plus.base.dialog.interfaces.dialog.IContextDialog;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.helpers.TargetPointsHelper;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.helpers.WaypointDialogHelper;
import net.osmand.plus.helpers.WaypointHelper;
import net.osmand.plus.helpers.LocationPointWrapper;
import net.osmand.plus.routepreparationmenu.data.PointType;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.views.controls.DynamicListView;
import net.osmand.plus.views.controls.DynamicListView.DragIcon;
import net.osmand.plus.views.controls.DynamicListViewCallbacks;
import net.osmand.plus.views.controls.StableArrayAdapter;
import net.osmand.plus.views.controls.SwipeDismissListViewTouchListener;
import net.osmand.plus.views.controls.SwipeDismissListViewTouchListener.DismissCallbacks;
import net.osmand.plus.views.controls.SwipeDismissListViewTouchListener.Undoable;
import net.osmand.plus.widgets.TextViewEx;
import net.osmand.plus.widgets.TextViewExProgress;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;

import static net.osmand.plus.helpers.WaypointDialogHelper.showOnMap;

public class WaypointsFragment extends BaseOsmAndFragment implements IContextDialog, ObservableScrollViewCallbacks,
		DynamicListViewCallbacks, WaypointDialogHelper.WaypointDialogHelperCallback, AddPointBottomSheetDialog.DialogListener {

	public static final String TAG = "WaypointsFragment";

	private View view;
	private View mainView;
	private DynamicListView listView;

	private StableArrayAdapter listAdapter;
	private AdapterView.OnItemClickListener listAdapterOnClickListener;
	private SwipeDismissListViewTouchListener swipeDismissListener;

	private StateChangedListener<Void> onStateChangedListener;
	private TargetPointsHelper targetPointsHelper;
	private WaypointsDialogController controller;

	private boolean portrait;
	private boolean wasDrawerDisabled;

	private boolean showWaypointOnMap;

	@Override
	protected boolean isUsedOnMap() {
		return true;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		targetPointsHelper = app.getTargetPointsHelper();
		controller = WaypointsDialogController.getInstance(app);
		if (controller != null) {
			controller.bindDialog(this);
		} else {
			dismiss();
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
		updateNightMode();
		MapActivity mapActivity = (MapActivity) requireActivity();
		OsmandApplication app = mapActivity.getMyApplication();
		portrait = AndroidUiHelper.isOrientationPortrait(mapActivity);

		view = inflater.inflate(R.layout.route_waypoints_fragment, parent, false);
		if (view == null) {
			return null;
		}
		AndroidUtils.addStatusBarPadding21v(mapActivity, view);
		mainView = view.findViewById(R.id.main_view);

		listView = view.findViewById(R.id.dash_list_view);
		listView.setDrawSelectorOnTop(true);
		listView.setDynamicListViewCallbacks(this);

		ImageView backButton = view.findViewById(R.id.back_button);
		Drawable icBack = getContentIcon(AndroidUtils.getNavigationIconResId(mapActivity));
		backButton.setImageDrawable(icBack);
		backButton.setOnClickListener(v -> dismiss());

		updateTitle();

		view.findViewById(R.id.sort_button).setOnClickListener(v -> {
			if (isHasActivePoints()) {
				MapActivity activity = getMapActivity();
				if (activity != null) {
					TargetOptionsBottomSheetDialogFragment fragment = new TargetOptionsBottomSheetDialogFragment();
					fragment.setUsedOnMap(true);
					fragment.show(activity.getSupportFragmentManager(), TargetOptionsBottomSheetDialogFragment.TAG);
				}
			}
		});

		if (!portrait) {
			TypedValue typedValueAttr = new TypedValue();
			int bgAttrId = AndroidUtils.isLayoutRtl(mapActivity) ? R.attr.right_menu_view_bg : R.attr.left_menu_view_bg;
			mapActivity.getTheme().resolveAttribute(bgAttrId, typedValueAttr, true);
			mainView.setBackgroundResource(typedValueAttr.resourceId);
			mainView.setLayoutParams(new FrameLayout.LayoutParams(getResources().getDimensionPixelSize(R.dimen.dashboard_land_width), ViewGroup.LayoutParams.MATCH_PARENT));
			int width = getResources().getDimensionPixelSize(R.dimen.dashboard_land_width) - getResources().getDimensionPixelSize(R.dimen.dashboard_land_shadow_width);
			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);

			params.gravity = Gravity.BOTTOM | Gravity.START;
			view.findViewById(R.id.control_buttons).setLayoutParams(params);
		}

		view.setOnClickListener(v -> dismiss());

		mainView.setOnClickListener(null);

		swipeDismissListener = new SwipeDismissListViewTouchListener(mapActivity, listView,
				new DismissCallbacks() {

					@Override
					public boolean canDismiss(int position) {
						StableArrayAdapter stableAdapter = listAdapter;
						if (stableAdapter != null) {
							List<Object> activeObjects = stableAdapter.getActiveObjects();
							Object obj = stableAdapter.getItem(position);
							if (obj instanceof LocationPointWrapper w) {
								if (w.getPoint() instanceof TargetPoint) {
									return !((TargetPoint) w.getPoint()).start;
								}
							}
							return activeObjects.contains(obj);
						}
						return false;
					}

					@Override
					public Undoable onDismiss(int position) {
						StableArrayAdapter stableAdapter = listAdapter;
						if (stableAdapter != null) {
							Object item = stableAdapter.getItem(position);
							stableAdapter.setNotifyOnChange(false);
							stableAdapter.remove(item);
							stableAdapter.getObjects().remove(item);
							stableAdapter.getActiveObjects().remove(item);
							stableAdapter.refreshData();
							stableAdapter.notifyDataSetChanged();
							controller.onApplyChanges(stableAdapter.getActiveObjects());
						}
						return null;
					}

					@Override
					public void onHidePopup() {
					}
				});

		FrameLayout addButton = view.findViewById(R.id.add_button);
		addButton.setOnClickListener(v -> {
			MapActivity activity = getMapActivity();
			if (activity != null) {
				Bundle arguments = new Bundle();
				arguments.putString(AddPointBottomSheetDialog.POINT_TYPE_KEY, PointType.INTERMEDIATE.name());
				AddPointBottomSheetDialog fragment = new AddPointBottomSheetDialog();
				fragment.setArguments(arguments);
				fragment.setUsedOnMap(true);
				fragment.setListener(WaypointsFragment.this);
				fragment.show(activity.getSupportFragmentManager(), AddPointBottomSheetDialog.TAG);
			}
		});

		FrameLayout clearButton = view.findViewById(R.id.clear_all_button);
		TextView clearButtonDescr = view.findViewById(R.id.clear_all_button_descr);
		clearButtonDescr.setText(R.string.shared_string_clear_all);
		clearButton.setOnClickListener(v -> controller.onClearClicked(() -> {
			updateTitle();
			reloadAdapter();
		}));

		View applyButton = view.findViewById(R.id.start_button);
		applyButton.setOnClickListener(
				v -> controller.onApplyChanges(listAdapter.getActiveObjects(), this::dismiss));

		View cancelButton = view.findViewById(R.id.cancel_button);
		TextViewEx cancelTitle = view.findViewById(R.id.cancel_button_descr);
		cancelTitle.setText(R.string.shared_string_cancel);
		cancelButton.setOnClickListener(
				v -> controller.onCancelChanges(this::dismiss));

		onStateChangedListener = change -> app.runInUIThread(() -> {
			reloadAdapter();
			updateTitle();
		});

		updateListAdapter();
		applyDayNightMode();

		return view;
	}

	private boolean isHasActivePoints() {
		boolean hasActivePoints = false;
		List<Object> items = listAdapter.getActiveObjects();
		if (!items.isEmpty()) {
			if (items.size() > 1) {
				hasActivePoints = true;
			} else {
				Object item = items.get(0);
				if (item instanceof LocationPointWrapper w) {
					if (w.getPoint() instanceof TargetPoint) {
						hasActivePoints = !((TargetPoint) w.point).start;
					}
				} else {
					hasActivePoints = true;
				}
			}
		}
		return hasActivePoints;
	}

	@Override
	public void onResume() {
		super.onResume();
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			MapRouteInfoMenu.waypointsVisible = true;
			wasDrawerDisabled = mapActivity.isDrawerDisabled();
			mapActivity.getDashboard().getWaypointDialogHelper().addHelperCallback(this);
			targetPointsHelper.addListener(onStateChangedListener);
			if (!wasDrawerDisabled) {
				mapActivity.disableDrawer();
			}
			updateRouteCalculationProgress(0);
			updateControlsVisibility(false, false);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			MapRouteInfoMenu.waypointsVisible = false;
			mapActivity.getDashboard().getWaypointDialogHelper().removeHelperCallback(this);
			targetPointsHelper.removeListener(onStateChangedListener);
			if (!wasDrawerDisabled) {
				mapActivity.enableDrawer();
			}
			updateControlsVisibility(true, controller.isUseRouteInfoMenu());
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (controller != null) {
			controller.onDestroy();
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		onDismiss();
	}

	@Override
	public void reloadAdapter() {
		StableArrayAdapter stableAdapter = listAdapter;
		if (stableAdapter != null) {
			reloadListAdapter(stableAdapter);
			setDynamicListItems(listView, stableAdapter);
		}
	}

	@Override
	public void onItemSwapping(int position) {
	}

	@Override
	public void onWindowVisibilityChanged(int visibility) {
	}

	@Override
	public void deleteWaypoint(int position) {
		deleteSwipeItem(position);
	}

	@Override
	public void onItemsSwapped(List<Object> items) {
		controller.onApplyChanges(items);
	}

	@Nullable
	private MapActivity getMapActivity() {
		return (MapActivity) getActivity();
	}

	public void applyDayNightMode() {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			return;
		}
		boolean landscapeLayout = !portrait;
		boolean nightMode = app.getDaynightHelper().isNightMode(ThemeUsageContext.OVER_MAP);
		int colorActive = ContextCompat.getColor(mapActivity, ColorUtilities.getActiveColorId(nightMode));
		if (!landscapeLayout) {
			AndroidUtils.setBackground(mapActivity, mainView, nightMode, R.drawable.route_info_menu_bg_light, R.drawable.route_info_menu_bg_dark);
		} else {
			AndroidUtils.setBackground(mapActivity, mainView, nightMode, R.drawable.route_info_menu_bg_left_light, R.drawable.route_info_menu_bg_left_dark);
		}

		((TextView) view.findViewById(R.id.sort_button)).setTextColor(colorActive);
		((TextView) view.findViewById(R.id.add_button_descr)).setTextColor(colorActive);
		((TextView) view.findViewById(R.id.clear_all_button_descr)).setTextColor(colorActive);
		((TextView) view.findViewById(R.id.title)).setTextColor(ColorUtilities.getPrimaryTextColor(mapActivity, nightMode));

		FrameLayout addButton = view.findViewById(R.id.add_button);
		TextView addButtonDescr = view.findViewById(R.id.add_button_descr);

		addButtonDescr.setText(R.string.shared_string_add);
		addButtonDescr.setCompoundDrawablesWithIntrinsicBounds(getPaintedContentIcon(R.drawable.ic_action_plus, colorActive), null, null, null);
		AndroidUtils.setBackground(mapActivity, addButton, nightMode, R.drawable.btn_border_light, R.drawable.btn_border_dark);
		AndroidUtils.setBackground(mapActivity, addButtonDescr, nightMode, R.drawable.ripple_light, R.drawable.ripple_dark);

		FrameLayout clearButton = view.findViewById(R.id.clear_all_button);
		TextView clearButtonDescr = view.findViewById(R.id.clear_all_button_descr);
		clearButtonDescr.setText(R.string.shared_string_clear_all);
		clearButtonDescr.setCompoundDrawablesWithIntrinsicBounds(getPaintedContentIcon(R.drawable.ic_action_clear_all, colorActive), null, null, null);

		AndroidUtils.setBackground(mapActivity, clearButton, nightMode, R.drawable.btn_border_light, R.drawable.btn_border_dark);
		AndroidUtils.setBackground(mapActivity, clearButtonDescr, nightMode, R.drawable.ripple_light, R.drawable.ripple_dark);
		AndroidUtils.setBackground(mapActivity, view.findViewById(R.id.cancel_button), ColorUtilities.getCardAndListBackgroundColorId(nightMode));
		AndroidUtils.setBackground(mapActivity, view.findViewById(R.id.controls_divider), ColorUtilities.getDividerColorId(nightMode));

		((TextView) view.findViewById(R.id.cancel_button_descr)).setTextColor(colorActive);

		TextViewExProgress startButtonText = view.findViewById(R.id.start_button_descr);
		ProgressBar progressBar = view.findViewById(R.id.progress_bar_button);
		startButtonText.setText(getText(R.string.shared_string_apply));

		int progressTextColor = nightMode ? R.color.text_color_secondary_dark : R.color.active_buttons_and_links_text_light;
		setupRouteCalculationButtonProgressBar(progressBar, startButtonText, progressTextColor);
	}

	public void reloadListAdapter(ArrayAdapter<Object> listAdapter) {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			return;
		}
		app.getWaypointHelper().removeVisibleLocationPoint(new ArrayList<>());

		listAdapter.setNotifyOnChange(false);
		listAdapter.clear();
		List<Object> points = controller.getTargetPoints();
		for (Object point : points) {
			listAdapter.add(point);
		}
		if (listAdapter instanceof StableArrayAdapter stableArrayAdapter) {
			stableArrayAdapter.updateObjects(points, controller.getActivePoints(points));
		}
		listAdapter.notifyDataSetChanged();
	}

	public AdapterView.OnItemClickListener getDrawerItemClickListener(FragmentActivity ctx,
	                                                                  ArrayAdapter<Object> listAdapter) {
		return (adapterView, view, item, l) -> {
			if (listAdapter.getItem(item) instanceof LocationPointWrapper ps) {
				showWaypointOnMap = true;
				dismiss();
				showOnMap(app, ctx, ps.getPoint(), false);
			}
		};
	}

	public StableArrayAdapter getWaypointsDrawerAdapter(boolean edit, List<LocationPointWrapper> deletedPoints,
	                                                    MapActivity ctx, boolean flat, boolean nightMode) {
		List<Object> points = controller.getTargetPoints();
		List<Object> activePoints = controller.getActivePoints(points);

		StableArrayAdapter listAdapter = new StableArrayAdapter(ctx,
				R.layout.route_waypoint_item, R.id.waypoint_text, points, activePoints) {

			@NonNull
			@Override
			public View getView(int position, View convertView, @NonNull ViewGroup parent) {
				View v = convertView;
				Object obj = getItem(position);
				if (obj instanceof LocationPointWrapper point) {
					v = updateWaypointItemView(edit, deletedPoints, ctx, v, point, this, nightMode, flat, position);
				}
				return v;
			}
		};

		for (Object p : points) {
			if (p instanceof LocationPointWrapper w) {
				if (w.type == WaypointHelper.TARGETS) {
					TargetPoint t = (TargetPoint) w.point;
					if (t.getOriginalPointDescription() != null
							&& t.getOriginalPointDescription().isSearchingAddress(ctx)) {
						AddressLookupRequest lookupRequest = new AddressLookupRequest(t.getLatLon(), address -> reloadListAdapter(listAdapter), null);
						ctx.getMyApplication().getGeocodingLookupService().lookupAddress(lookupRequest);
					}
				}
			}
		}

		return listAdapter;
	}

	public void updateControlsVisibility(boolean visible, boolean openingRouteInfo) {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			int visibility = visible ? View.VISIBLE : View.GONE;
			AndroidUiHelper.setVisibility(mapActivity, visibility,
					R.id.map_center_info,
					R.id.map_left_widgets_panel);
			if (!openingRouteInfo) {
				mapActivity.findViewById(R.id.map_right_widgets_panel).setVisibility(visibility);
				if (!portrait) {
					mapActivity.getMapPositionManager().setMapPositionShiftedX(!visible);
				}
			}
			mapActivity.refreshMap();
		}
	}

	public void updateRouteCalculationProgress(int progress) {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null || view == null) {
			return;
		}
		ProgressBar progressBarButton = view.findViewById(R.id.progress_bar_button);
		if (progressBarButton != null) {
			if (progressBarButton.getVisibility() != View.VISIBLE) {
				progressBarButton.setVisibility(View.VISIBLE);
			}
			progressBarButton.setProgress(progress);
		}
		TextViewExProgress textViewExProgress = view.findViewById(R.id.start_button_descr);
		textViewExProgress.percent = progress / 100f;
		textViewExProgress.invalidate();
	}

	private void setupRouteCalculationButtonProgressBar(@NonNull ProgressBar pb, @NonNull TextViewExProgress textProgress, @ColorRes int progressTextColor) {
			int bgColor = ContextCompat.getColor(app, nightMode ? R.color.activity_background_color_dark : R.color.activity_background_color_light);
			int progressColor = ContextCompat.getColor(app, ColorUtilities.getActiveColorId(nightMode));
			pb.setProgressDrawable(AndroidUtils.createProgressDrawable(bgColor, ContextCompat.getColor(app, progressTextColor)));
			textProgress.paint.setColor(progressColor);
			textProgress.setTextColor(ContextCompat.getColor(app, R.color.text_color_secondary_dark));
	}

	private void setDynamicListItems(DynamicListView listView, StableArrayAdapter listAdapter) {
		listView.setItemsList(listAdapter.getObjects());
		listView.setActiveItemsList(listAdapter.getActiveObjects());
	}

	private void updateListAdapter() {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			return;
		}
		List<LocationPointWrapper> deletedPoints = new ArrayList<>();
		listView.setEmptyView(null);
		StableArrayAdapter listAdapter = getWaypointsDrawerAdapter(true, deletedPoints, mapActivity, false, nightMode);
		AdapterView.OnItemClickListener listener = getDrawerItemClickListener(mapActivity, listAdapter);
		setDynamicListItems(listView, listAdapter);
		updateListAdapter(listAdapter, listener);
	}

	private void updateListAdapter(StableArrayAdapter listAdapter, AdapterView.OnItemClickListener listener) {
		this.listAdapter = listAdapter;
		listAdapterOnClickListener = listener;
		if (listView != null) {
			listView.setAdapter(listAdapter);
			if (listAdapterOnClickListener != null) {
				listView.setOnItemClickListener((parent, view, position, id)
						-> listAdapterOnClickListener.onItemClick(parent, view, position - listView.getHeaderViewsCount(), id));
			} else {
				listView.setOnItemClickListener(null);
			}
		}
	}

	private void deleteSwipeItem(int position) {
		if (swipeDismissListener != null) {
			swipeDismissListener.delete(position);
		}
	}

	private void updateTitle() {
		if (isAdded()) {
			TextViewEx title = view.findViewById(R.id.title);
			int pointsSize = targetPointsHelper.getAllPoints().size();
			String text = getString(R.string.shared_string_target_points);
			String countText = "(" + (pointsSize != 0 ? pointsSize : 1) + ")";
			text = getString(R.string.ltr_or_rtl_combine_via_space, text, countText);
			title.setText(text);
		}
	}

	@NonNull
	private View updateWaypointItemView(boolean edit, List<LocationPointWrapper> deletedPoints,
	                                    MapActivity mapActivity, View v,
	                                    LocationPointWrapper point,
	                                    ArrayAdapter<Object> adapter, boolean nightMode,
	                                    boolean flat, int position) {
		OsmandApplication app = mapActivity.getMyApplication();
		WaypointDialogHelper helper = mapActivity.getDashboard().getWaypointDialogHelper();
		if (v == null || v.findViewById(R.id.info_close) == null) {
			v = inflate(R.layout.route_waypoint_item, null);
		}
		v.setBackgroundColor(ColorUtilities.getCardAndListBackgroundColor(mapActivity, nightMode));
		updatePointInfoView(mapActivity, v, point, true, nightMode, edit, false);

		ImageView move = v.findViewById(R.id.info_move);
		ImageButton remove = v.findViewById(R.id.info_close);
		View topDivider = v.findViewById(R.id.top_divider);

		if (!edit) {
			remove.setVisibility(View.GONE);
			move.setVisibility(View.GONE);
		} else {
			boolean targets = point.type == WaypointHelper.TARGETS;
			boolean notFlatTargets = targets && !flat;
			boolean startPoint = notFlatTargets && ((TargetPoint) point.point).start;
			boolean canRemove = !targets || !targetPointsHelper.getIntermediatePoints().isEmpty();

			remove.setVisibility(View.VISIBLE);
			remove.setImageDrawable(app.getUIUtilities().getThemedIcon(R.drawable.ic_action_remove_dark));
			remove.setEnabled(canRemove);
			remove.setAlpha(canRemove ? 1 : .5f);
			if (canRemove) {
				if (notFlatTargets && startPoint) {
					remove.setOnClickListener(view -> {
						if (targetPointsHelper.getPointToStart() == null) {
							if (!targetPointsHelper.getIntermediatePoints().isEmpty()) {
								WaypointDialogHelper.replaceStartWithFirstIntermediate(mapActivity);
							}
						} else {
							targetPointsHelper.setStartPoint(null, true, null);
							WaypointDialogHelper.updateControls(mapActivity);
						}
					});
				} else {
					remove.setOnClickListener(view -> WaypointDialogHelper.deletePoint(mapActivity, adapter, point, deletedPoints));
				}
			}

			AndroidUtils.setBackground(mapActivity, topDivider, ColorUtilities.getDividerColorId(nightMode));
			topDivider.setVisibility(position != 0 ? View.VISIBLE : View.GONE);

			move.setVisibility(notFlatTargets ? View.VISIBLE : View.GONE);
			if (notFlatTargets) {
				move.setImageDrawable(app.getUIUtilities().getIcon(R.drawable.ic_action_item_move, R.color.icon_color_default_light));
				move.setTag((DragIcon) () -> {
					// do nothing
				});
			}
		}

		return v;
	}

	private static void updatePointInfoView(MapActivity mapActivity,
	                                        View localView, LocationPointWrapper ps,
	                                        boolean mapCenter, boolean nightMode,
	                                        boolean edit, boolean topBar) {
		OsmandApplication app = mapActivity.getMyApplication();
		WaypointHelper wh = mapActivity.getMyApplication().getWaypointHelper();
		LocationPoint point = ps.getPoint();
		TextView text = localView.findViewById(R.id.waypoint_text);
		if (!topBar) {
			text.setTextColor(ColorUtilities.getPrimaryTextColor(mapActivity, nightMode));
		}
		TextView textShadow = localView.findViewById(R.id.waypoint_text_shadow);
		if (!edit) {
			localView.setOnClickListener(view -> showOnMap(app, mapActivity, point, mapCenter));
		}
		TextView textDist = localView.findViewById(R.id.waypoint_dist);
		textDist.setTextColor(ColorUtilities.getActiveColor(mapActivity, nightMode));
		((ImageView) localView.findViewById(R.id.waypoint_icon)).setImageDrawable(ps.getDrawable(mapActivity, app, nightMode));
		int dist = -1;
		boolean startPoint = ps.type == WaypointHelper.TARGETS && ((TargetPoint) ps.point).start;
		if (!startPoint) {
			if (!wh.isRouteCalculated()) {
				dist = (int) MapUtils.getDistance(mapActivity.getMapView().getLatitude(), mapActivity.getMapView().getLongitude(),
						point.getLatitude(), point.getLongitude());
			} else {
				dist = wh.getRouteDistance(ps);
			}
		}

		if (dist > 0) {
			textDist.setText(OsmAndFormatter.getFormattedDistance(dist, app));
		} else {
			textDist.setText("");
		}

		TextView textDeviation = localView.findViewById(R.id.waypoint_deviation);
		if (textDeviation != null) {
			if (dist > 0 && ps.deviationDistance > 0) {
				String devStr = "+" + OsmAndFormatter.getFormattedDistance(ps.deviationDistance, app);
				textDeviation.setText(devStr);
				if (!topBar) {
					int colorId = R.color.icon_color_default_light;
					textDeviation.setTextColor(ContextCompat.getColor(app, R.color.icon_color_default_light));
					if (ps.deviationDirectionRight) {
						textDeviation.setCompoundDrawablesWithIntrinsicBounds(
								app.getUIUtilities().getIcon(R.drawable.ic_small_turn_right, colorId),
								null, null, null);
					} else {
						textDeviation.setCompoundDrawablesWithIntrinsicBounds(
								app.getUIUtilities().getIcon(R.drawable.ic_small_turn_left, colorId),
								null, null, null);
					}
				}
				textDeviation.setVisibility(View.VISIBLE);
			} else {
				textDeviation.setText("");
				textDeviation.setVisibility(View.GONE);
			}
		}

		String descr;
		PointDescription pd = point.getPointDescription(app);
		if (Algorithms.isEmpty(pd.getName())) {
			descr = pd.getTypeName();
		} else {
			descr = pd.getName();
		}

		if (textShadow != null) {
			textShadow.setText(descr);
		}
		text.setText(descr);

		String pointDescription = "";
		TextView descText = localView.findViewById(R.id.waypoint_desc_text);
		if (descText != null) {
			descText.setTextColor(ContextCompat.getColor(app, R.color.text_color_secondary_light));
			switch (ps.type) {
				case WaypointHelper.TARGETS:
					TargetPoint targetPoint = (TargetPoint) ps.point;
					if (targetPoint.start) {
						pointDescription = mapActivity.getResources().getString(R.string.starting_point);
					} else {
						pointDescription = getPointDescription(mapActivity, targetPoint).getTypeName();
					}
					break;

				case WaypointHelper.FAVORITES:
					FavouritePoint favPoint = (FavouritePoint) ps.point;
					pointDescription = Algorithms.isEmpty(favPoint.getCategory()) ? mapActivity.getResources().getString(R.string.shared_string_favorites) : favPoint.getCategory();
					break;
			}
		}

		if (Algorithms.objectEquals(descr, pointDescription)) {
			pointDescription = "";
		}
		if (dist > 0 && !Algorithms.isEmpty(pointDescription)) {
			pointDescription = "  •  " + pointDescription;
		}
		if (descText != null) {
			descText.setText(pointDescription);
		}
	}

	private static PointDescription getPointDescription(Context ctx, TargetPoint point) {
		if (!point.intermediate) {
			return new PointDescription(PointDescription.POINT_TYPE_TARGET, ctx.getString(R.string.route_descr_destination) + ":",
					point.getOnlyName());
		} else {
			return new PointDescription(PointDescription.POINT_TYPE_TARGET, ctx.getString(R.string.intermediate_waypoint) + ": " + (point.index + 1), point.getOnlyName());
		}
	}

	private void onDismiss() {
		try {
			if (controller.isUseRouteInfoMenu() && !showWaypointOnMap) {
				MapActivity mapActivity = (MapActivity) getActivity();
				if (mapActivity != null) {
					mapActivity.getMapActions().showRouteInfoControlDialog();
				}
			}
		} catch (Exception e) {
			//
		}
	}

	private void dismiss() {
		MapActivity mapActivity = (MapActivity) getActivity();
		if (mapActivity != null) {
			mapActivity.getSupportFragmentManager()
					.beginTransaction()
					.remove(this)
					.commitAllowingStateLoss();
		}
	}

	@Override
	public void onRequestToSelectOnMap(@NonNull PointType pointType) {
		MapActivity mapActivity = (MapActivity) getActivity();
		if (mapActivity != null) {
			mapActivity.getMapRouteInfoMenu().selectOnScreen(pointType, TAG);
			controller.setUseRouteInfoMenu(false);
			dismiss();
		}
	}

	public static boolean showInstance(@NonNull FragmentActivity activity) {
		return showInstance(activity, false);
	}

	public static boolean showInstance(@NonNull FragmentActivity activity, boolean useRouteInfoMenu) {
		FragmentManager fragmentManager = activity.getSupportFragmentManager();
		OsmandApplication app = (OsmandApplication) activity.getApplicationContext();
		if (AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
			WaypointsDialogController.createInstance(app, useRouteInfoMenu);

			WaypointsFragment fragment = new WaypointsFragment();
			fragmentManager.beginTransaction()
					.add(R.id.routeMenuContainer, fragment, TAG)
					.addToBackStack(TAG)
					.commitAllowingStateLoss();
			return true;
		}
		return false;
	}
}