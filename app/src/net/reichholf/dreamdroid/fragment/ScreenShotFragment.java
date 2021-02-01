/* © 2010 Stephan Reichholf <stephan at reichholf dot net>
 *
 * Licensed under the Create-Commons Attribution-Noncommercial-Share Alike 3.0 Unported
 * http://creativecommons.org/licenses/by-nc-sa/3.0/
 */

package net.reichholf.dreamdroid.fragment;


import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.evernote.android.state.State;
import com.github.chrisbanes.photoview.PhotoView;

import net.reichholf.dreamdroid.R;
import net.reichholf.dreamdroid.fragment.abs.BaseFragment;
import net.reichholf.dreamdroid.fragment.helper.HttpFragmentHelper;
import net.reichholf.dreamdroid.helpers.NameValuePair;
import net.reichholf.dreamdroid.helpers.Statics;
import net.reichholf.dreamdroid.helpers.enigma2.URIStore;
import net.reichholf.dreamdroid.loader.AsyncByteLoader;
import net.reichholf.dreamdroid.loader.LoaderResult;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Objects;

/**
 * Allows fetching and showing the actual TV-Screen content
 *
 * @author sre
 */
public class ScreenShotFragment extends BaseFragment implements
        LoaderCallbacks<LoaderResult<byte[]>>, SwipeRefreshLayout.OnRefreshListener {
    public static final int TYPE_OSD = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_ALL = 2;
    public static final int FORMAT_JPG = 0;
    public static final int FORMAT_PNG = 1;

    public static final String KEY_TYPE = "type";
    public static final String KEY_FORMAT = "format";
    public static final String KEY_SIZE = "size";
    public static final String KEY_FILENAME = "filename";

    private static final String BUNDLE_KEY_RETAIN = "retain";

    private static final int CREATE_FILE = 1;

    private final boolean mSetTitle;
    private final boolean mActionsEnabled;
    private PhotoView mImageView;
    private int mType;
    private int mFormat;
    private int mSize;
    private String mFilename;
    @State
    public byte[] mRawImage;
    private MediaScannerConnection mScannerConn;
    private HttpFragmentHelper mHttpHelper;

    @Override
    public void onRefresh() {
        reload();
    }

    @Override
    public boolean hasHeader() {
        return false;
    }

    private static class DummyMediaScannerConnectionClient implements MediaScannerConnectionClient {
        @Override
        public void onMediaScannerConnected() {
        }

        @Override
        public void onScanCompleted(String arg0, Uri arg1) {
        }

    }

    public ScreenShotFragment() {
        super();
        shouldRetain(true);
        mHttpHelper = new HttpFragmentHelper();
        mActionsEnabled = true;
        mSetTitle = true;
    }

    public ScreenShotFragment(boolean retainInstance, boolean actionsEnabled, boolean setTitle) {
        super();
        shouldRetain(retainInstance);
        mActionsEnabled = actionsEnabled;
        mSetTitle = setTitle;
    }

    private void shouldRetain(boolean retainInstance) {
        Bundle args = new Bundle();
        args.putBoolean(BUNDLE_KEY_RETAIN, retainInstance);
        setArguments(args);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        assert getArguments() != null;
        mShouldRetainInstance = getArguments().getBoolean(BUNDLE_KEY_RETAIN);
        super.onCreate(savedInstanceState);
        if (mHttpHelper == null)
            mHttpHelper = new HttpFragmentHelper(this);
        else
            mHttpHelper.bindToFragment(this);

        setHasOptionsMenu(true);
        if (mSetTitle)
            initTitles(getString(R.string.screenshot));
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getAppCompatActivity().setTitle(getText(R.string.screenshot));
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.screenshot, null);

        mImageView = view.findViewById(R.id.screenshoot);
        mImageView.setBackgroundColor(Color.BLACK);

        Bundle extras = getArguments();

        if (extras == null) {
            extras = new Bundle();
        }

        mType = extras.getInt(KEY_TYPE, TYPE_ALL);
        mFormat = extras.getInt(KEY_FORMAT, mType == TYPE_OSD ? FORMAT_PNG : FORMAT_JPG);
        mSize = extras.getInt(KEY_SIZE, -1);
        mFilename = extras.getString(KEY_FILENAME);

        if (mRawImage == null) {
            mRawImage = new byte[0];
        }
        return view;
    }

    public void onViewCreated(@NotNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mHttpHelper.onViewCreated(view, savedInstanceState);
        SwipeRefreshLayout SwipeRefreshLayout = view.findViewById(R.id.ptr_layout);
        SwipeRefreshLayout.setEnabled(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        mScannerConn = new MediaScannerConnection(getAppCompatActivity(), new DummyMediaScannerConnectionClient());
        mScannerConn.connect();
        onScreenshotAvailable(mRawImage);
    }

    @Override
    public void onPause() {
        mScannerConn.disconnect();
        mScannerConn = null;
        super.onPause();
    }

    @Override
    public void createOptionsMenu(Menu menu, MenuInflater inflater) {
        if (menu.findItem(R.id.menu_reload) == null && mActionsEnabled) {
            inflater.inflate(R.menu.reload, menu);
            inflater.inflate(R.menu.save, menu);
            inflater.inflate(R.menu.share, menu);
        }
    }

    private void setShareIntent() throws IOException {
        ContentValues values = new ContentValues(1);
        values.put(MediaStore.Images.Media.MIME_TYPE, String.format("image/%s", getFileExtension()));
        Uri uri = requireContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assert uri != null;
        OutputStream os = requireContext().getContentResolver().openOutputStream(uri);
        assert os != null;
        os.write(mRawImage);

        Intent sharingIntent = new Intent(Intent.ACTION_SEND);
        sharingIntent.setType(String.format("image/%s", getFileExtension()));
        sharingIntent.putExtra(Intent.EXTRA_STREAM, uri);
        requireContext().startActivity(Intent.createChooser(sharingIntent, "Sharing screenshot"));
    }

    private String getFileExtension() {
        if (mFormat == FORMAT_JPG) {
            return "jpeg";
        } else if (mFormat == FORMAT_PNG) {
            return "png";
        }
        return "";
    }

    @Override
    public void onSaveInstanceState(@NotNull Bundle outState) {
        if (mScannerConn != null)
            mScannerConn.disconnect();
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case Statics.ITEM_RELOAD:
                reload();
                break;
            case Statics.ITEM_SAVE:
                saveToFile();
                break;
            case Statics.ITEM_SHARE:
                try {
                    setShareIntent();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }

        return true;
    }

    /**
     * @param bytes The screenshot bytes
     */
    private void onScreenshotAvailable(byte[] bytes) {
        if (!isAdded())
            return;
        mRawImage = bytes;
        mImageView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
        mImageView.getAttacher().update();
    }

    protected void reload() {
        mHttpHelper.onLoadStarted();
        ArrayList<NameValuePair> params = new ArrayList<>();

        switch (mType) {
            case (TYPE_OSD):
                params.add(new NameValuePair("o", " "));
                params.add(new NameValuePair("n", " "));
                break;
            case (TYPE_VIDEO):
                params.add(new NameValuePair("v", " "));
                break;
            case (TYPE_ALL):
                break;
        }

        switch (mFormat) {
            case (FORMAT_JPG):
                params.add(new NameValuePair("format", "jpg"));
                break;
            case (FORMAT_PNG):
                params.add(new NameValuePair("format", "png"));
                break;
        }

        if (mSize > 0) {
            params.add(new NameValuePair("r", String.valueOf(mSize)));
        }
        mFilename = "dreamDroid-" + (new GregorianCalendar().getTimeInMillis()) / 1000 + "." + getFileExtension();

        params.add(new NameValuePair("filename", mFilename));

        Bundle args = new Bundle();
        args.putString("uri", URIStore.SCREENSHOT);
        args.putSerializable("params", params);
        LoaderManager.getInstance(this).restartLoader(0, args, this);
    }

    private void saveToFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mFormat == FORMAT_JPG ? "image/jpeg" : "image/png");
        intent.putExtra(Intent.EXTRA_TITLE, "dreamDroid-" + (new GregorianCalendar().getTimeInMillis()) / 1000);
        startActivityForResult(intent, CREATE_FILE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == CREATE_FILE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                try {
                    if (uri == null) {
                        throw new AssertionError();
                    }
                    OutputStream file = requireContext().getContentResolver().openOutputStream(uri);
                    Objects.requireNonNull(file).write(mRawImage);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    @NonNull
    @Override
    public Loader<LoaderResult<byte[]>> onCreateLoader(int id, Bundle args) {
        return new AsyncByteLoader(getAppCompatActivity(), args);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<LoaderResult<byte[]>> loader, LoaderResult<byte[]> result) {
        mHttpHelper.onLoadFinished();
        if (!result.isError()) {
            if (result.getResult().length > 0) {
                onScreenshotAvailable(result.getResult());
            } else
                showToast(getString(R.string.error));
        } else {
            showToast(result.getErrorText());
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<LoaderResult<byte[]>> loader) {
        mHttpHelper.onLoadFinished();
    }

    protected void showToast(String toastText) {
        Toast toast = Toast.makeText(getAppCompatActivity(), toastText, Toast.LENGTH_LONG);
        toast.show();
    }

}
