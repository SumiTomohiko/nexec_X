package jp.gr.java_conf.neko_daisuki.nexec_x.activity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import jp.gr.java_conf.neko_daisuki.android.nexec.client.share.SessionId;
import jp.gr.java_conf.neko_daisuki.android.nexec.client.util.NexecClient;
import jp.gr.java_conf.neko_daisuki.android.nexec.client.util.NexecHost;
import jp.gr.java_conf.neko_daisuki.android.nexec.client.util.NexecUtil;
import jp.gr.java_conf.neko_daisuki.nexec_x.R;
import jp.gr.java_conf.neko_daisuki.nexec_x.fragment.ApplicationsFragment;
import jp.gr.java_conf.neko_daisuki.nexec_x.fragment.XFragment;
import jp.gr.java_conf.neko_daisuki.nexec_x.model.Application;

public class MainActivity extends FragmentActivity implements ApplicationsFragment.OnSelectedListener, XFragment.OnInitializingViewListener { 

    private interface AfterResumeProc {

        public void run(SessionId savedSessionId);
    }

    private class ConnectAfterResumeProc implements AfterResumeProc {

        @Override
        public void run(SessionId savedSessionId) {
            mNexecClient.connect(savedSessionId);

            boolean isRunning = !savedSessionId.isNull();
            Fragment f = isRunning ? XFragment.newInstance()
                                   : ApplicationsFragment.newInstance();
            showFragment(f);
        }
    }

    private interface ResultProc {

        public static class Nop implements ResultProc {

            @Override
            public void run(int requestCode, int resultCode, Intent data) {
            }
        }

        public static final ResultProc NOP = new Nop();

        public void run(int requestCode, int resultCode, Intent data);
    }

    private class HostPreferenceResultProc implements ResultProc {

        @Override
        public void run(int requestCode, int resultCode, Intent data) {
            writeHost(NexecUtil.getHost(data));
        }
    }

    private class ConfirmResultProc implements ResultProc {

        private class ExecuteAfterResumeProc implements AfterResumeProc {

            private Intent mData;

            public ExecuteAfterResumeProc(Intent data) {
                mData = data;
            }

            @Override
            public void run(SessionId savedSessionId) {
                mProgressDialog.show();
                mStderr.clear();
                mNexecClient.setOnXInvalidateListener(mFirstOnXInvalidateListener);
                mNexecClient.execute(NexecUtil.getSessionId(mData));
                invalidateOptionsMenu();
                showFragment(XFragment.newInstance());
            }
        }

        @Override
        public void run(int requestCode, int resultCode, Intent data) {
            mAfterResumeProc = new ExecuteAfterResumeProc(data);
        }
    }

    private static class ResultProcs {

        private SparseArray<ResultProc> mProcs = new SparseArray<ResultProc>();

        public void put(int requestCode, int resultCode, ResultProc proc) {
            mProcs.put(computeKey(requestCode, resultCode), proc);
        }

        public void run(int requestCode, int resultCode, Intent data) {
            ResultProc entry = mProcs.get(computeKey(requestCode, resultCode));
            ResultProc proc = entry != null ? entry : ResultProc.NOP;
            proc.run(requestCode, resultCode, data);
        }

        private int computeKey(int requestCode, int resultCode) {
            return (requestCode << 16) + resultCode;
        }
    }

    private interface MenuProc {

        public void run(MenuItem item);
    }

    private class AboutMenuProc implements MenuProc {

        @Override
        public void run(MenuItem item) {
            AboutActivity.show(MainActivity.this);
        }
    }

    private class PressRightButtonMenuProc implements MenuProc {

        @Override
        public void run(MenuItem item) {
            mNexecClient.xRightButtonPress();
            mPressingRightButton = true;
            invalidateX();
        }
    }

    private class ReleaseRightButtonMenuProc implements MenuProc {

        @Override
        public void run(MenuItem item) {
            mNexecClient.xRightButtonRelease();
            mPressingRightButton = false;
            invalidateX();
        }
    }

    private class PressLeftButtonMenuProc implements MenuProc {

        @Override
        public void run(MenuItem item) {
            mNexecClient.xLeftButtonPress();
            mPressingLeftButton = true;
            invalidateX();
        }
    }

    private class ReleaseLeftButtonMenuProc implements MenuProc {

        @Override
        public void run(MenuItem item) {
            mNexecClient.xLeftButtonRelease();
            mPressingLeftButton = false;
            invalidateX();
        }
    }

    private class QuitSessionMenuProc implements MenuProc {

        @Override
        public void run(MenuItem item) {
            mNexecClient.quit();
            invalidateOptionsMenu();
            showFragment(ApplicationsFragment.newInstance());
        }
    }

    private class HostPreferenceMenuProc implements MenuProc {

        @Override
        public void run(MenuItem item) {
            int requestCode = REQUEST_HOST_PREFERENCE;
            NexecUtil.startHostPreferenceActivity(MainActivity.this,
                                                  requestCode, mHost.getHost(),
                                                  mHost.getPort());
        }
    }

    private abstract class ZoomMenuProc implements MenuProc {

        public abstract void run(MenuItem item);

        protected void showScale() {
            XFragment fragment = getXFragment();
            showShortToast(String.format("x%d", fragment.getScale()));
        }
    }

    private class ZoomInMenuProc extends ZoomMenuProc {

        @Override
        public void run(MenuItem item) {
            getXFragment().zoomIn();
            showScale();
        }
    }

    private class ZoomOutMenuProc extends ZoomMenuProc {

        @Override
        public void run(MenuItem item) {
            getXFragment().zoomOut();
            showScale();
        }
    }

    private abstract class BaseOnXInvalidateListener implements NexecClient.OnXInvalidateListener {

        @Override
        public void onInvalidate(int left, int top, int right, int bottom) {
            preInvalidate();
            getXFragment().postInvalidate();
        }

        protected abstract void preInvalidate();
    }

    private class FirstOnXInvalidateListener extends BaseOnXInvalidateListener {

        @Override
        protected void preInvalidate() {
            mProgressDialog.dismiss();
            mNexecClient.setOnXInvalidateListener(mOnXInvalidateListener);
        }
    }

    private class OnXInvalidateListener extends BaseOnXInvalidateListener {

        @Override
        protected void preInvalidate() {
        }
    }

    private class OnExitListener implements NexecClient.OnExitListener {

        @Override
        public void onExit(NexecClient nexecClient, int exitCode) {
            showToast(String.format("exit: %d", exitCode));
            mProgressDialog.dismiss();

            int size = mStderr.size();
            byte[] buf = new byte[size];
            for (int i = 0; i < size; i++) {
                buf[i] = mStderr.get(i).byteValue();
            }
            String s;
            try {
                s = new String(buf, "UTF-8");
            }
            catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                return;
            }
            Log.i(LOG_TAG, s);
            //showToast(s);
        }
    }

    private class Toaster implements Runnable {

        private String mMessage;
        private int mDuration;

        public Toaster(String message, int duration) {
            mMessage = message;
            mDuration = duration;
        }

        @Override
        public void run() {
            String name = getString(getApplicationInfo().labelRes);
            String s = String.format("%s: %s", name, mMessage);
            Toast.makeText(MainActivity.this, s, mDuration).show();
        }
    }

    private class LongToaster extends Toaster {

        public LongToaster(String message) {
            super(message, Toast.LENGTH_LONG);
        }
    }

    private class ShortToaster extends Toaster {

        public ShortToaster(String message) {
            super(message, Toast.LENGTH_SHORT);
        }
    }

    private class OnStderrListener implements NexecClient.OnStderrListener {

        @Override
        public void onWrite(NexecClient nexecClient, byte[] buf) {
            for (int i = 0; i < buf.length; i++) {
                mStderr.add(Byte.valueOf(buf[i]));
            }
        }
    }

    private static final String PATH_SESSION_ID = "session_id";
    private static final int REQUEST_CONFIRM = 42;
    private static final int REQUEST_HOST_PREFERENCE = 43;
    private static final String DEFAULT_HOST = "neko-daisuki.ddo.jp";
    private static final String LOG_TAG = "activity";

    // documents
    private NexecHost mHost;
    private List<Byte> mStderr = new ArrayList<Byte>();

    // views
    private boolean mPressingLeftButton = false;
    private boolean mPressingRightButton = false;
    private View mView;
    private Dialog mProgressDialog;

    // helpers
    private NexecClient mNexecClient;
    private SparseArray<MenuProc> mMenuProcs = new SparseArray<MenuProc>();
    private ResultProcs mResultProcs = new ResultProcs();
    private AfterResumeProc mAfterResumeProc = new ConnectAfterResumeProc();
    private NexecClient.OnXInvalidateListener mFirstOnXInvalidateListener;
    private NexecClient.OnXInvalidateListener mOnXInvalidateListener;

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        mMenuProcs.get(item.getItemId()).run(item);
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isNull = mNexecClient.getSessionId().isNull();
        menu.findItem(R.id.action_quit_session).setVisible(!isNull);

        menu.findItem(R.id.action_press_left_button).setVisible(!isNull && !mPressingLeftButton);
        menu.findItem(R.id.action_release_left_button).setVisible(!isNull && mPressingLeftButton);
        menu.findItem(R.id.action_press_right_button).setVisible(!isNull && !mPressingRightButton);
        menu.findItem(R.id.action_release_right_button).setVisible(!isNull && mPressingRightButton);
        menu.findItem(R.id.action_zoom_in).setVisible(!isNull);
        menu.findItem(R.id.action_zoom_out).setVisible(!isNull);

        return true;
    }

    @Override
    public void onSelected(ApplicationsFragment fragment,
                           Application application) {
        NexecClient.Settings settings = new NexecClient.Settings();
        settings.host = mHost.getHost();
        settings.port = mHost.getPort();
        settings.args = application.getArguments();
        settings.addEnvironment("DISPLAY", ":0");
        settings.files = new String[0];
        settings.xWidth = mView.getWidth();
        settings.xHeight = mView.getHeight();

        mNexecClient.request(settings, REQUEST_CONFIRM);
    }

    @Override
    public NexecClient onInitializingView(XFragment fragment) {
        return mNexecClient;
    }

    @Override
    protected void onPause() {
        super.onPause();
        writeSessionId(mNexecClient.getSessionId());
        mNexecClient.disconnect();
        new File(getApplicationDirectoryPath()).mkdirs();
        writeHost(mHost);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHost = readHost(getHostJsonPath());
        mAfterResumeProc.run(readSessionId());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mNexecClient = new NexecClient(this);
        mNexecClient.setOnExitListener(new OnExitListener());
        mNexecClient.setOnStderrListener(new OnStderrListener());
        mMenuProcs.put(R.id.action_quit_session, new QuitSessionMenuProc());
        mMenuProcs.put(R.id.action_press_left_button,
                       new PressLeftButtonMenuProc());
        mMenuProcs.put(R.id.action_release_left_button,
                       new ReleaseLeftButtonMenuProc());
        mMenuProcs.put(R.id.action_press_right_button,
                       new PressRightButtonMenuProc());
        mMenuProcs.put(R.id.action_release_right_button,
                       new ReleaseRightButtonMenuProc());
        mMenuProcs.put(R.id.action_zoom_in, new ZoomInMenuProc());
        mMenuProcs.put(R.id.action_zoom_out, new ZoomOutMenuProc());
        mMenuProcs.put(R.id.action_host_preference,
                       new HostPreferenceMenuProc());
        mMenuProcs.put(R.id.action_about_this_app, new AboutMenuProc());
        mResultProcs.put(REQUEST_CONFIRM, RESULT_OK, new ConfirmResultProc());
        mResultProcs.put(REQUEST_HOST_PREFERENCE, RESULT_OK,
                         new HostPreferenceResultProc());
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        dialog.setCancelable(false);
        dialog.setTitle("Please wait");
        dialog.setMessage("Initializing X...");
        mProgressDialog = dialog;
        mFirstOnXInvalidateListener = new FirstOnXInvalidateListener();
        mOnXInvalidateListener = new OnXInvalidateListener();

        mView = findViewById(R.id.fragment_container);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        mResultProcs.run(requestCode, resultCode, data);
    }

    private SessionId readSessionId() {
        InputStream in;
        try {
            in = openFileInput(PATH_SESSION_ID);
        }
        catch (FileNotFoundException e) {
            return SessionId.NULL;
        }
        try {
            try {
                Reader reader = new InputStreamReader(in);
                return new SessionId(new BufferedReader(reader).readLine());
            }
            finally {
                in.close();
            }
        }
        catch (IOException e) {
            handleException("read session id", e);
            return SessionId.NULL;
        }
    }

    private void writeSessionId(SessionId sessionId) {
        OutputStream out;
        try {
            out = openFileOutput(PATH_SESSION_ID, 0);
        }
        catch (FileNotFoundException e) {
            handleException("open session id", e);
            return;
        }
        try {
            try {
                OutputStreamWriter osw = new OutputStreamWriter(out);
                try {
                    PrintWriter writer = new PrintWriter(osw);
                    try {
                        writer.write(sessionId.toString());
                    }
                    finally {
                        writer.close();
                    }
                }
                finally {
                    osw.close();
                }
            }
            finally {
                out.close();
            }
        }
        catch (IOException e) {
            handleException("write session id", e);
        }
    }

    private void showShortToast(String msg) {
        runOnUiThread(new ShortToaster(msg));
    }

    private void showToast(String msg) {
        runOnUiThread(new LongToaster(msg));
    }

    private void handleException(String msg, Throwable e) {
        e.printStackTrace();
        showToast(String.format("%s: %s", msg, e.getMessage()));
    }

    private String getApplicationDirectoryPath() {
        String fmt = "%s/.android-nexec-client-x";
        return String.format(fmt, Environment.getExternalStorageDirectory());
    }

    private String getHostJsonPath() {
        return String.format("%s/host.json", getApplicationDirectoryPath());
    }

    private void writeHost(NexecHost host) {
        writeHost(getHostJsonPath(), host);
    }

    private void writeHost(String path, NexecHost host) {
        try {
            NexecUtil.writeHostToJson(path, host);
        }
        catch (IOException e) {
            handleException("Cannot write host information", e);
        }
    }

    private NexecHost readHost(String path) {
        try {
            return NexecUtil.readHostFromJson(path);
        }
        catch (FileNotFoundException e) {
            return new NexecHost(DEFAULT_HOST);
        }
        catch (IOException e) {
            handleException("Cannot read host information", e);
            return new NexecHost(DEFAULT_HOST);
        }
    }

    private void invalidateX() {
        invalidateOptionsMenu();
        getXFragment().postInvalidate();
    }

    private void showFragment(Fragment fragment) {
        FragmentManager manager = getSupportFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
    }

    private XFragment getXFragment() {
        return (XFragment)getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    }
}