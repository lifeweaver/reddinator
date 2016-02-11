/*
 * Copyright 2013 Michael Boyde Wallace (http://wallaceit.com.au)
 * This file is part of Reddinator.
 *
 * Reddinator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Reddinator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Reddinator (COPYING). If not, see <http://www.gnu.org/licenses/>.
 */
package au.com.wallaceit.reddinator.activity;

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.joanzapata.android.iconify.IconDrawable;
import com.joanzapata.android.iconify.Iconify;

import java.lang.reflect.Method;
import java.util.Date;

import au.com.wallaceit.reddinator.R;
import au.com.wallaceit.reddinator.Reddinator;
import au.com.wallaceit.reddinator.core.ThemeManager;
import au.com.wallaceit.reddinator.service.MailCheckService;
import au.com.wallaceit.reddinator.ui.AccountFeedFragment;
import au.com.wallaceit.reddinator.ui.SimpleTabsWidget;

public class AccountActivity extends FragmentActivity implements AccountFeedFragment.ActivityInterface {

    private Reddinator global;
    private SharedPreferences prefs;
    private MenuItem messageIcon;
    private ActionBar actionBar;
    private BroadcastReceiver inboxReceiver;
    private RedditPageAdapter pageAdapter;
    private SimpleTabsWidget tabsIndicator;
    private Resources resources;
    private int actionbarIconColor = Reddinator.getActionbarIconColor();
    public static final String ACTION_SAVED = "saved";
    private String section = "overview";

    /**
     * (non-Javadoc)
     *
     * @see FragmentActivity#onCreate(Bundle)
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    protected void onCreate(Bundle savedInstanceState) {
        // set window flags
        getWindow().requestFeature(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().requestFeature(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        // request loading bar first
        getWindow().requestFeature(Window.FEATURE_PROGRESS);

        super.onCreate(savedInstanceState);
        global = ((Reddinator) AccountActivity.this.getApplicationContext());
        if (!global.mRedditData.isLoggedIn()){
            global.mRedditData.initiateLogin(AccountActivity.this, false);
            Toast.makeText(AccountActivity.this, "Reddit login required", Toast.LENGTH_LONG).show();
            this.finish();
            return;
        }
        prefs = PreferenceManager.getDefaultSharedPreferences(AccountActivity.this);
        resources = getResources();

        // get actionbar and set home button, pad the icon
        actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        ImageView view = (ImageView) findViewById(android.R.id.home);
        if (view != null) {
            view.setPadding(5, 0, 5, 0);
        }
        // set content view
        setContentView(R.layout.view_account);
        // Setup View Pager and widget
        ViewPager viewPager = (ViewPager) findViewById(R.id.tab_content);
        pageAdapter = new RedditPageAdapter(getSupportFragmentManager());
        viewPager.setAdapter(pageAdapter);
        LinearLayout tabLayout = (LinearLayout) findViewById(R.id.tabs);
        final HorizontalScrollView scrollView = (HorizontalScrollView) findViewById(R.id.tab_widget);
        tabsIndicator = new SimpleTabsWidget(AccountActivity.this, tabLayout, scrollView);
        tabsIndicator.setViewPager(viewPager);
        // theme
        updateTheme();

        if (getIntent().getAction()!=null && getIntent().getAction().equals(ACTION_SAVED)){
            section = ACTION_SAVED;
            viewPager.setCurrentItem(6);
            tabsIndicator.setTab(6);
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(ScrollView.FOCUS_RIGHT);
                }
            });
        }

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}
            @Override
            public void onPageSelected(int position) {
                Fragment fragment = pageAdapter.getRegisteredFragment(position);
                if (fragment!=null)
                    ((AccountFeedFragment) fragment).load();
            }
            @Override
            public void onPageScrollStateChanged(int state) {}
        });
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == 3) {
            updateTheme();
            Fragment fragment;
            for (int i =0; i<pageAdapter.registeredFragments.size(); i++) {
                fragment = pageAdapter.getRegisteredFragment(i);
                if (fragment != null && fragment.getClass().getSimpleName().equals("AccountFeedFragment"))
                    ((AccountFeedFragment) fragment).updateTheme();
            }
        }
    }

    private void updateTheme(){
        ThemeManager.Theme theme = getCurrentTheme();
        tabsIndicator.setBackgroundColor(Color.parseColor(theme.getValue("header_color")));
        tabsIndicator.setInidicatorColor(Color.parseColor(theme.getValue("tab_indicator")));
        tabsIndicator.setTextColor(Color.parseColor(theme.getValue("header_text")));
    }

    public ThemeManager.Theme getCurrentTheme(){
        return global.mThemeManager.getActiveTheme("appthemepref");
    }

    public void onResume(){
        super.onResume();
        // Register receiver & check for new messages if logged in, enabled and due
        int checkPref = Integer.parseInt(prefs.getString("mail_check_pref", "300000"));
        if (global.mRedditData.isLoggedIn() || checkPref!=0)
        if ((global.mRedditData.getLastUserUpdateTime()+checkPref)<(new Date()).getTime()) {
            inboxReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // update inbox indicator
                    setInboxIcon();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(MailCheckService.MAIL_CHECK_COMPLETE);
            registerReceiver(inboxReceiver, filter);

            MailCheckService.checkMail(AccountActivity.this, MailCheckService.ACTIVITY_CHECK_ACTION);
        }

        setInboxIcon();
    }

    public void onPause(){
        super.onPause();
        if (inboxReceiver!=null) {
            unregisterReceiver(inboxReceiver);
            inboxReceiver = null;
        }
    }

    @Override
    public void finish() {
        ViewGroup view = (ViewGroup) getWindow().getDecorView();
        view.removeAllViews();
        super.finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.account_menu, menu);
        // set options menu view
        (menu.findItem(R.id.menu_submit)).setIcon(new IconDrawable(this, Iconify.IconValue.fa_pencil).color(actionbarIconColor).actionBarSize());
        (menu.findItem(R.id.menu_viewonreddit)).setIcon(new IconDrawable(this, Iconify.IconValue.fa_globe).color(actionbarIconColor).actionBarSize());
        (menu.findItem(R.id.menu_prefs)).setIcon(new IconDrawable(this, Iconify.IconValue.fa_wrench).color(actionbarIconColor).actionBarSize());
        (menu.findItem(R.id.menu_about)).setIcon(new IconDrawable(this, Iconify.IconValue.fa_info_circle).color(actionbarIconColor).actionBarSize());
        // set inbox icon color based on inbox count
        messageIcon = (menu.findItem(R.id.menu_inbox));
        setInboxIcon();

        return super.onCreateOptionsMenu(menu);
    }

    private void setInboxIcon(){
        if (messageIcon!=null){
            int inboxColor = global.mRedditData.getInboxCount()>0?Color.parseColor("#E06B6C"): actionbarIconColor;
            messageIcon.setIcon(new IconDrawable(this, Iconify.IconValue.fa_envelope).color(inboxColor).actionBarSize());
        }
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu)
    {
        if(featureId == Window.FEATURE_ACTION_BAR && menu != null){
            if(menu.getClass().getSimpleName().equals("MenuBuilder")){
                try{
                    Method m = menu.getClass().getDeclaredMethod(
                            "setOptionalIconsVisible", Boolean.TYPE);
                    m.setAccessible(true);
                    m.invoke(menu, true);
                }
                catch(NoSuchMethodException e){
                    System.out.println("Could not display action icons in menu");
                }
                catch(Exception e){
                    throw new RuntimeException(e);
                }
            }
        }
        return super.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case android.R.id.home:
                this.finish();
                break;

            case R.id.menu_inbox:
                Intent inboxIntent = new Intent(AccountActivity.this, MessagesActivity.class);
                if (global.mRedditData.getInboxCount()>0) {
                    inboxIntent.setAction(MessagesActivity.ACTION_UNREAD);
                }
                startActivity(inboxIntent);
                break;

            case R.id.menu_submit:
                Intent submitIntent = new Intent(AccountActivity.this, SubmitActivity.class);
                startActivity(submitIntent);
                break;

            case R.id.menu_viewonreddit:
                Intent accnIntent = new Intent(AccountActivity.this, WebViewActivity.class);
                accnIntent.putExtra("url", global.getDefaultMobileSite()+"/user/"+global.mRedditData.getUsername()+"/");
                startActivity(accnIntent);
                break;

            case R.id.menu_prefs:
                Intent intent = new Intent(AccountActivity.this, PrefsActivity.class);
                intent.putExtra("fromapp", true);
                startActivityForResult(intent, 0);
                break;

            case R.id.menu_about:
                Reddinator.showInfoDialog(this, true);
                break;

            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    public void setTitleText(final String title){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                actionBar.setTitle(title);
            }
        });
    }

    /**
     * (non-Javadoc)
     *
     * @see FragmentActivity#onSaveInstanceState(Bundle)
     */
    protected void onSaveInstanceState(Bundle outState) {
        //outState.putString("tab", mTabHost.getCurrentTabTag()); //save the tab selected
        super.onSaveInstanceState(outState);
    }

    class RedditPageAdapter extends FragmentPagerAdapter {

        SparseArray<Fragment> registeredFragments = new SparseArray<>();

        public RedditPageAdapter(FragmentManager fragmentManager){
            super(fragmentManager);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position){
                case 0: return resources.getString(R.string.overview);
                case 1: return resources.getString(R.string.submitted);
                case 2: return resources.getString(R.string.comments);
                case 3: return resources.getString(R.string.upvoted);
                case 4: return resources.getString(R.string.downvoted);
                case 5: return resources.getString(R.string.hidden);
                case 6: return resources.getString(R.string.saved);
                case 7: return resources.getString(R.string.gilded);
            }
            return resources.getString(R.string.app_name);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                default:
                case 0:
                    return AccountFeedFragment.init("overview", section.equals("overview"));
                case 1:
                    return AccountFeedFragment.init("submitted", section.equals("submitted"));
                case 2:
                    return AccountFeedFragment.init("comments", section.equals("comments"));
                case 3:
                    return AccountFeedFragment.init("upvoted", section.equals("upvoted"));
                case 4:
                    return AccountFeedFragment.init("downvoted", section.equals("downvoted"));
                case 5:
                    return AccountFeedFragment.init("hidden", section.equals("hidden"));
                case 6:
                    return AccountFeedFragment.init("saved", section.equals("saved"));
                case 7:
                    return AccountFeedFragment.init("gilded", section.equals("gilded"));
            }
        }

        @Override
        public int getCount() {
            return 8;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            Fragment fragment = (Fragment) super.instantiateItem(container, position);
            registeredFragments.put(position, fragment);
            return fragment;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            registeredFragments.remove(position);
            super.destroyItem(container, position, object);
        }

        public Fragment getRegisteredFragment(int position) {
            return registeredFragments.get(position);
        }

    }

}
