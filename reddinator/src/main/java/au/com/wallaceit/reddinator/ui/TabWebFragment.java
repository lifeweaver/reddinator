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
package au.com.wallaceit.reddinator.ui;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import au.com.wallaceit.reddinator.R;
import au.com.wallaceit.reddinator.activity.ViewRedditActivity;
import au.com.wallaceit.reddinator.core.Utilities;

public class TabWebFragment extends Fragment {
    /**
     * (non-Javadoc)
     *
     * @see android.support.v4.app.Fragment#onCreateView(android.view.LayoutInflater, android.view.ViewGroup, android.os.Bundle)
     */
    private Context mContext;
    public WebView mWebView;
    private boolean mFirstTime = true;
    private LinearLayout ll;
    //private Bundle WVState;
    public View mFullSView;
    private WebChromeClient.CustomViewCallback mFullSCallback;
    public WebChromeClient mChromeClient;
    private Activity mActivity;
    private String url;

    public static TabWebFragment init(String url, int fontsize, boolean load) {
        TabWebFragment webTab = new TabWebFragment();
        // Supply val input as an argument.
        Bundle args = new Bundle();
        args.putString("url", url);
        args.putInt("fontsize", fontsize);
        args.putBoolean("load", load);
        webTab.setArguments(args);
        return webTab;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        //mWebView.restoreState(savedInstanceState);
    }

    private boolean loaded = false;
    public void load(){
        if (!loaded) {
            mWebView.loadUrl(url);
            loaded = true;
        }
    }

    public void load(String url){
        this.url = url;
        load();
    }


    @SuppressLint("SetJavaScriptEnabled")
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mContext = this.getActivity();
        if (container == null) {
            return null;
        }
        if (mFirstTime) {
            final boolean load = getArguments().getBoolean("load");
            int fontsize = getArguments().getInt("fontsize");
            url = getArguments().getString("url");
            // setup progressbar
            mActivity = this.getActivity();
            mActivity.getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_VISIBILITY_ON);
            ll = (LinearLayout) inflater.inflate(R.layout.webtab, container, false);
            mWebView = ll.findViewById(R.id.webView1);
            // fixes for activity_webview not taking keyboard input on some devices
            mWebView.getSettings().setLoadWithOverviewMode(true);
            mWebView.getSettings().setUseWideViewPort(true);
            mWebView.getSettings().setJavaScriptEnabled(true); // enable ecmascript
            mWebView.getSettings().setDomStorageEnabled(true); // some video sites require dom storage
            mWebView.getSettings().setSupportZoom(true);
            mWebView.getSettings().setBuiltInZoomControls(true);
            // always display on screen zoom when no multitouch
            boolean multi = getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH);
            mWebView.getSettings().setDisplayZoomControls(!multi);
            mWebView.getSettings().setDefaultFontSize(fontsize);
            // enable cookies
            CookieManager.getInstance().setAcceptCookie(true);
            // Prevent cookie from redirecting to desktop site
            CookieManager.getInstance().setCookie(".reddit.com", "mweb-no-redirect=");

            mChromeClient = newchromeclient;

            mWebView.setWebChromeClient(mChromeClient);
            mWebView.setWebViewClient(new WebViewClient() {
                boolean clearhistory = true;

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (clearhistory) {
                        clearhistory = false;
                        mWebView.clearHistory();
                    }
                    if (url.contains(".reddit.com/"))
                        Utilities.executeJavascriptInWebview(mWebView,
                                "var css = '.XPromoPill { display: none !important; }',\n" +
                                        "    head = document.head || document.getElementsByTagName('head')[0],\n" +
                                        "    style = document.createElement('style');" +
                                        "head.appendChild(style);\n" +
                                        "style.type = 'text/css';\n" +
                                        "style.appendChild(document.createTextNode(css));\n" +
                                        "let clickRedditButtonCounter = 0;\n" +
                                        "const clickRedditButton = function() {\n" +
                                        "	clickRedditButtonCounter++;\n" +
                                        "	const shredditExperienceTree = document.querySelector('shreddit-experience-tree');\n" +
                                        "	if(shredditExperienceTree != undefined) {\n" +
                                        "		const shredditAsyncLoader = shredditExperienceTree.shadowRoot.querySelector('shreddit-async-loader');\n" +
                                        "		if(shredditAsyncLoader != undefined) {\n" +
                                        "			const xpromoAppSelector = shredditAsyncLoader.shadowRoot.querySelector('xpromo-app-selector');	\n" +
                                        "			if(xpromoAppSelector != undefined) {\n" +
                                        "				const button = xpromoAppSelector.shadowRoot.querySelector('#secondary-button');\n" +
                                        "				if(button != undefined) {\n" +
                                        "					button.click();\n" +
                                        "					return\n" +
                                        "				}\n" +
                                        "			}\n" +
                                        "		}\n" +
                                        "	}\n" +
                                        "	\n" +
                                        "	if(clickRedditButtonCounter < 60) {\n" +
                                        "		setTimeout(clickRedditButton, 1000);\n" +
                                        "	}\n" +
                                        "}\n" +
                                        "setTimeout(clickRedditButton, 100)"
                                );

                    super.onPageFinished(view, url);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {

                    if (url.contains("file://") || url.contains("https://") || url.contains("http://")){
                        return false;
                    }

                    // handle app urls; prevents 404 page displaying for unknown schemas.
                    // catch activity not found exceptions
                    try {
                        view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored){
                        // no op
                    }

                    return true;
                }
            });
            getActivity().registerForContextMenu(mWebView);
            if (load) load();
            mFirstTime = false;
            //System.out.println("Created fragment");
        } else {
            ((ViewGroup) ll.getParent()).removeView(ll);
        }

        return ll;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        //mWebView.saveState(outState);
    }

    @Override
    public void onPause() {
        super.onPause();
        //mWebView.saveState(WVState);
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        if (mWebView != null) {
            mWebView.removeAllViews();
            mWebView.destroy();
        }
    }

    // web chrome client
    private WebChromeClient newchromeclient = new WebChromeClient() {
        ActionBar actionBar;
        private FrameLayout mVideoFrame;
        private View mTabcontainer;
        private LinearLayout rootLayout;

        public void onProgressChanged(WebView view, int progress) {
            if(isAdded()) {
                boolean voteinprogress = ((ViewRedditActivity) mActivity).voteInProgress();
                //Make the bar disappear after URL is loaded, and changes string to Loading...
                if (!voteinprogress)
                    mActivity.setTitle(getResources().getString(R.string.loading)); // supress if vote in progress
                mActivity.setProgress(progress * 100); //Make the bar disappear after URL is loaded
                // Return the app name after finish loading
                if (progress == 100) {
                    if (!voteinprogress)
                        mActivity.setTitle(R.string.app_name); // dont reset title if vote in prog. voting function will do that.
                }
                actionBar = mActivity.getActionBar();
            }
        }

        FrameLayout.LayoutParams LayoutParameters = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);

        @TargetApi(Build.VERSION_CODES.KITKAT)
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            // if a view already exists then immediately terminate the new one
            if (mFullSView != null) {
                callback.onCustomViewHidden();
                return;
            }
            // create custom view to show
            mVideoFrame = new FrameLayout(mContext);
            mVideoFrame.setLayoutParams(LayoutParameters);
            mVideoFrame.setBackgroundResource(android.R.color.black);
            mVideoFrame.addView(view);
            mVideoFrame.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                    | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            view.setLayoutParams(LayoutParameters);
            mFullSView = view;
            mFullSCallback = callback;
            actionBar.hide();
            ((Activity) mContext).getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mVideoFrame.setVisibility(View.VISIBLE);
            // add view to root layout
            rootLayout = ((Activity) mContext).findViewById(R.id.contentview);
            rootLayout.addView(mVideoFrame);
            // get main content view and hide
            mTabcontainer = ((Activity) mContext).findViewById(R.id.sliding_layout);
            mTabcontainer.setVisibility(View.GONE);
        }

        @Override
        public void onHideCustomView() {
            if (mFullSView != null) {
                // Hide the custom view.
                mFullSView.setVisibility(View.GONE);
                // Remove the custom view from its container.  
                mVideoFrame.removeView(mFullSView);
                mFullSView = null;
                mVideoFrame.setVisibility(View.GONE);
                mFullSCallback.onCustomViewHidden();
                // remove fullscreen
                actionBar.show();
                ((Activity) mContext).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                mTabcontainer.setVisibility(View.VISIBLE);

                rootLayout.removeView(mVideoFrame);
            }
        }
    };
}
