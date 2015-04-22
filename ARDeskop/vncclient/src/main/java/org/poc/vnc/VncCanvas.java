package org.poc.vnc;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.Toast;

import org.poc.android.bc.BCFactory;
import org.poc.common.IViewRenderer;
import org.poc.common.Utils;
import org.poc.vnc.domain.*;

import java.io.IOException;
import java.util.zip.Inflater;

/**
 * Created by joel on 4/19/15.
 */
public class VncCanvas extends ImageView {
    private final static String TAG = "VncCanvas";
    private final static boolean LOCAL_LOGV = true;

    public void setViewRenderer(IViewRenderer viewRenderer) {
        this.m_ViewRenderer = viewRenderer;
    }

    private IViewRenderer m_ViewRenderer;

    private AbstractScaling scaling;

    // Available to activity
    private int mouseX, mouseY;

    private IConnection m_Connection;
    private ColorModel pendingColorModel = ColorModel.C24bit;
    private ColorModel colorModel = null;
    private int bytesPerPixel = 0;
    private int[] colorPalette = null;

    // VNC protocol connection
    public RfbProto rfb;

    // Internal bitmap data
    AbstractBitmapData bitmapData;
    public Handler handler = new Handler();

    // ZRLE encoder's data.
    private byte[] zrleBuf;
    private int[] zrleTilePixels;
    private ZlibInStream zrleInStream;

    // Zlib encoder's data.
    private byte[] zlibBuf;
    private Inflater zlibInflater;

    private Paint handleRREPaint;


    // Useful shortcuts for modifier masks.

    public final static int CTRL_MASK  = KeyEvent.META_SYM_ON;
    public final static int SHIFT_MASK = KeyEvent.META_SHIFT_ON;
    public final static int META_MASK  = 0;
    public final static int ALT_MASK   = KeyEvent.META_ALT_ON;

    private static final int MOUSE_BUTTON_NONE = 0;
    static final int MOUSE_BUTTON_LEFT = 1;
    static final int MOUSE_BUTTON_MIDDLE = 2;
    static final int MOUSE_BUTTON_RIGHT = 4;
    static final int MOUSE_BUTTON_SCROLL_UP = 8;
    static final int MOUSE_BUTTON_SCROLL_DOWN = 16;


    private IConnection connection;
    private ProgressDialog mProgressDialog;
    private boolean maintainConnection = true;

    // VNC Encoding parameters
    private boolean useCopyRect = false; // TODO CopyRect is not working
    private int preferredEncoding = -1;

    // Unimplemented VNC encoding parameters
    private boolean requestCursorUpdates = false;
    private boolean ignoreCursorUpdates = true;

    // Unimplemented TIGHT encoding parameters
    private int compressLevel = -1;
    private int jpegQuality = -1;

    // Used to determine if encoding update is necessary
    private int[] encodingsSaved = new int[20];
    private int nEncodingsSaved = 0;

    public VncCanvas(final Context context, AttributeSet attrs)
    {
        super(context, attrs);
        //scrollRunnable = new MouseScrollRunnable();
        handleRREPaint = new Paint();
        handleRREPaint.setStyle(Paint.Style.FILL);
    }


    void initializeVncCanvas(Connection bean, final Runnable setModes)
    {
        this.connection = bean;
        this.pendingColorModel = ColorModel.C24bit;


        // Startup the RFB thread with a nifty progess dialog
        mProgressDialog = ProgressDialog.show(getContext(), "Connecting...", "Establishing handshake.\nPlease wait...", true, true, new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                //closeConnection();
                handler.post(new Runnable() {
                    public void run() {
                        Utils.showErrorMessage(getContext(), "VNC connection aborted!");
                    }
                });
            }
        });

        //final Display display = mProgressDialog.getWindow().getWindowManager().getDefaultDisplay();
        DisplayMetrics displaymetrics = new DisplayMetrics();
        mProgressDialog.getWindow().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        final int screenWidth = displaymetrics.widthPixels;
        final int screenHeight = displaymetrics.heightPixels;

        Thread t = new Thread() {
            public void run() {
                try {
                    connectAndAuthenticate(connection.getUserName(),connection.getPassword());
                    doProtocolInitialisation(screenWidth, screenHeight);
                    handler.post(new Runnable() {
                        public void run() {
                            mProgressDialog.setMessage("Downloading first frame.\nPlease wait...");
                        }
                    });
                    processNormalProtocol(getContext(), mProgressDialog, setModes);
                } catch (Throwable e) {
                    if (maintainConnection) {
                        Log.e(TAG, e.toString());
                        e.printStackTrace();
                        // Ensure we dismiss the progress dialog
                        // before we fatal error finish
                        if (mProgressDialog.isShowing())
                            mProgressDialog.dismiss();
                        if (e instanceof OutOfMemoryError) {
                            // TODO  Not sure if this will happen but...
                            // figure out how to gracefully notify the user
                            // Instantiating an alert dialog here doesn't work
                            // because we are out of memory. :(
                        } else {
                            String error = "VNC connection failed!";
                            if (e.getMessage() != null && (e.getMessage().indexOf("authentication") > -1)) {
                                error = "VNC authentication failed!";
                            }
                            final String error_ = error + "<br>" + e.getLocalizedMessage();
                            handler.post(new Runnable() {
                                public void run() {
                                    Utils.showFatalErrorMessage(getContext(), error_);
                                }
                            });
                        }
                    }
                }
            }
        };
        t.start();
    }

    void connectAndAuthenticate(String us,String pw) throws Exception {
        Log.i(TAG, "Connecting to " + connection.getAddress() + ", port " + connection.getPort() + "...");

        rfb = new RfbProto(connection.getAddress(), Integer.parseInt(connection.getPort()));
        if (LOCAL_LOGV) Log.v(TAG, "Connected to server");

        // <RepeaterMagic>
        /*if (connection.getUseRepeater() && connection.getRepeaterId() != null && connection.getRepeaterId().length()>0) {
            Log.i(TAG, "Negotiating repeater/proxy connection");
            byte[] protocolMsg = new byte[12];
            rfb.is.read(protocolMsg);
            byte[] buffer = new byte[250];
            System.arraycopy(connection.getRepeaterId().getBytes(), 0, buffer, 0, connection.getRepeaterId().length());
            rfb.os.write(buffer);
        }*/
        // </RepeaterMagic>

        rfb.readVersionMsg();
        Log.i(TAG, "RFB server supports protocol version " + rfb.serverMajor + "" + rfb.serverMinor);

        rfb.writeVersionMsg();
        Log.i(TAG, "Using RFB protocol version " + rfb.clientMajor + "" + rfb.clientMinor);

        int bitPref=0;
        if(connection.getUserName().length()>0)
            bitPref|=1;
        Log.d("debug","bitPref="+bitPref);
        int secType = rfb.negotiateSecurity(bitPref);
        int authType;
        if (secType == RfbProto.SecTypeTight) {
            rfb.initCapabilities();
            rfb.setupTunneling();
            authType = rfb.negotiateAuthenticationTight();
        } else if (secType == RfbProto.SecTypeUltra34) {
            rfb.prepareDH();
            authType = RfbProto.AuthUltra;
        } else {
            authType = secType;
        }

        switch (authType) {
            case RfbProto.AuthNone:
                Log.i(TAG, "No authentication needed");
                rfb.authenticateNone();
                break;
            case RfbProto.AuthVNC:
                Log.i(TAG, "VNC authentication needed");
                rfb.authenticateVNC(pw);
                break;
            case RfbProto.AuthUltra:
                rfb.authenticateDH(us,pw);
                break;
            default:
                throw new Exception("Unknown authentication scheme " + authType);
        }
    }

    public float getScale()
    {
        if (scaling == null)
            return 1;
        return scaling.getScale();
    }

    public int getVisibleWidth() {
        return (int)((double)getWidth() / getScale() + 0.5);
    }

    public int getVisibleHeight() {
        return (int)((double)getHeight() / getScale() + 0.5);
    }

    void doProtocolInitialisation(int dx, int dy) throws IOException {
        rfb.writeClientInit();
        rfb.readServerInit();

        Log.i(TAG, "Desktop name is " + rfb.desktopName);
        Log.i(TAG, "Desktop size is " + rfb.framebufferWidth + " x " + rfb.framebufferHeight);

        boolean useFull = false;
        int capacity = BCFactory.getInstance().getBCActivityManager().getMemoryClass(Utils.getActivityManager(getContext()));

        useFull = true;// For now true as default.
        /*if (connection.getForceFull() == BitmapImplHint.AUTO)
        {
            if (rfb.framebufferWidth * rfb.framebufferHeight * FullBufferBitmapData.CAPACITY_MULTIPLIER <= capacity * 1024 * 1024)
                useFull = true;
        }
        else
            useFull = (connection.getForceFull() == BitmapImplHint.FULL);*/
        if (! useFull)
            bitmapData=new LargeBitmapData(rfb,this,dx,dy,capacity);
        else
            bitmapData=new FullBufferBitmapData(rfb,this, capacity);
        mouseX=rfb.framebufferWidth/2;
        mouseY=rfb.framebufferHeight/2;

        setPixelFormat();
    }

    private void setPixelFormat() throws IOException {
        pendingColorModel.setPixelFormat(rfb);
        bytesPerPixel = pendingColorModel.bpp();
        colorPalette = pendingColorModel.palette();
        colorModel = pendingColorModel;
        pendingColorModel = null;
    }

    public void processNormalProtocol(final Context context, ProgressDialog pd, final Runnable setModes) throws Exception {
        try {
            bitmapData.writeFullUpdateRequest(false);

            handler.post(setModes);
            //
            // main dispatch loop
            //
            while (maintainConnection) {
                bitmapData.syncScroll();
                // Read message type from the server.
                int msgType = rfb.readServerMessageType();
                bitmapData.doneWaiting();
                // Process the message depending on its type.
                switch (msgType) {
                    case RfbProto.FramebufferUpdate:
                        rfb.readFramebufferUpdate();

                        for (int i = 0; i < rfb.updateNRects; i++) {
                            rfb.readFramebufferUpdateRectHdr();
                            int rx = rfb.updateRectX, ry = rfb.updateRectY;
                            int rw = rfb.updateRectW, rh = rfb.updateRectH;

                            if (rfb.updateRectEncoding == RfbProto.EncodingLastRect) {
                                Log.v(TAG, "rfb.EncodingLastRect");
                                break;
                            }

                            if (rfb.updateRectEncoding == RfbProto.EncodingNewFBSize) {
                                rfb.setFramebufferSize(rw, rh);
                                // - updateFramebufferSize();
                                Log.v(TAG, "rfb.EncodingNewFBSize");
                                break;
                            }

                            if (rfb.updateRectEncoding == RfbProto.EncodingXCursor || rfb.updateRectEncoding == RfbProto.EncodingRichCursor) {
                                // - handleCursorShapeUpdate(rfb.updateRectEncoding,
                                // rx,
                                // ry, rw, rh);
                                Log.v(TAG, "rfb.EncodingCursor");
                                continue;

                            }

                            if (rfb.updateRectEncoding == RfbProto.EncodingPointerPos) {
                                // This never actually happens
                                mouseX=rx;
                                mouseY=ry;
                                Log.v(TAG, "rfb.EncodingPointerPos");
                                continue;
                            }

                            rfb.startTiming();

                            switch (rfb.updateRectEncoding) {
                                case RfbProto.EncodingRaw:
                                    handleRawRect(rx, ry, rw, rh);
                                    break;
                                case RfbProto.EncodingCopyRect:
                                    handleCopyRect(rx, ry, rw, rh);
                                    Log.v(TAG, "CopyRect is Buggy!");
                                    break;
                                case RfbProto.EncodingRRE:
                                    handleRRERect(rx, ry, rw, rh);
                                    break;
                                case RfbProto.EncodingCoRRE:
                                    handleCoRRERect(rx, ry, rw, rh);
                                    break;
                                case RfbProto.EncodingHextile:
                                    handleHextileRect(rx, ry, rw, rh);
                                    break;
                                case RfbProto.EncodingZRLE:
                                    handleZRLERect(rx, ry, rw, rh);
                                    break;
                                case RfbProto.EncodingZlib:
                                    handleZlibRect(rx, ry, rw, rh);
                                    break;
                                default:
                                    Log.e(TAG, "Unknown RFB rectangle encoding " + rfb.updateRectEncoding + " (0x" + Integer.toHexString(rfb.updateRectEncoding) + ")");
                            }

                            rfb.stopTiming();

                            // Hide progress dialog
                            if (pd.isShowing())
                                pd.dismiss();
                        }

                        boolean fullUpdateNeeded = false;

                        if (pendingColorModel != null) {
                            setPixelFormat();
                            fullUpdateNeeded = true;
                        }

                        setEncodings(true);
                        bitmapData.writeFullUpdateRequest(!fullUpdateNeeded);

                        break;

                    case RfbProto.SetColourMapEntries:
                        throw new Exception("Can't handle SetColourMapEntries message");

                    case RfbProto.Bell:
                        handler.post( new Runnable() {
                            public void run() { Toast.makeText(context, "VNC Beep", Toast.LENGTH_SHORT); }
                        });
                        break;

                    case RfbProto.ServerCutText:
                        String s = rfb.readServerCutText();
                        if (s != null && s.length() > 0) {
                            // TODO implement cut & paste
                        }
                        break;

                    case RfbProto.TextChat:
                        // UltraVNC extension
                        String msg = rfb.readTextChatMsg();
                        if (msg != null && msg.length() > 0) {
                            // TODO implement chat interface
                        }
                        break;

                    default:
                        throw new Exception("Unknown RFB message type " + msgType);
                }
            }
        } catch (Exception e) {
            throw e;
        } finally {
            Log.v(TAG, "Closing VNC Connection");
            rfb.close();
        }
    }

    void handleRawRect(int x, int y, int w, int h) throws IOException {
        handleRawRect(x, y, w, h, true);
    }

    byte[] handleRawRectBuffer = new byte[128];
    void handleRawRect(int x, int y, int w, int h, boolean paint) throws IOException {
        boolean valid=bitmapData.validDraw(x, y, w, h);
        int[] pixels=bitmapData.bitmapPixels;
        if (bytesPerPixel == 1) {
            // 1 byte per pixel. Use palette lookup table.
            if (w > handleRawRectBuffer.length) {
                handleRawRectBuffer = new byte[w];
            }
            int i, offset;
            for (int dy = y; dy < y + h; dy++) {
                rfb.readFully(handleRawRectBuffer, 0, w);
                if ( ! valid)
                    continue;
                offset = bitmapData.offset(x, dy);
                for (i = 0; i < w; i++) {
                    pixels[offset + i] = colorPalette[0xFF & handleRawRectBuffer[i]];
                }
            }
        } else {
            // 4 bytes per pixel (argb) 24-bit color

            final int l = w * 4;
            if (l>handleRawRectBuffer.length) {
                handleRawRectBuffer = new byte[l];
            }
            int i, offset;
            for (int dy = y; dy < y + h; dy++) {
                rfb.readFully(handleRawRectBuffer, 0, l);
                if ( ! valid)
                    continue;
                offset = bitmapData.offset(x, dy);
                for (i = 0; i < w; i++) {
                    final int idx = i*4;
                    pixels[offset + i] = // 0xFF << 24 |
                            (handleRawRectBuffer[idx + 2] & 0xff) << 16 | (handleRawRectBuffer[idx + 1] & 0xff) << 8 | (handleRawRectBuffer[idx] & 0xff);
                }
            }
        }

        if ( ! valid)
            return;

        bitmapData.updateBitmap( x, y, w, h);

        if (paint)
            reDraw();
    }

    private boolean showDesktopInfo = true;
    private boolean repaintsEnabled = true;
    private Runnable reDraw = new Runnable() {
        public void run() {
            if (showDesktopInfo) {
                // Show a Toast with the desktop info on first frame draw.
                showDesktopInfo = false;
                showConnectionInfo();
            }
            if (bitmapData != null)
                bitmapData.updateView(VncCanvas.this);
        }
    };

    public void showConnectionInfo() {
        String msg = rfb.desktopName;
        int idx = rfb.desktopName.indexOf("(");
        if (idx > -1) {
            // Breakup actual desktop name from IP addresses for improved
            // readability
            String dn = rfb.desktopName.substring(0, idx).trim();
            String ip = rfb.desktopName.substring(idx).trim();
            msg = dn + "\n" + ip;
        }
        msg += "\n" + rfb.framebufferWidth + "x" + rfb.framebufferHeight;
        String enc = getEncoding();
        // Encoding might not be set when we display this message
        if (enc != null && !enc.equals(""))
            msg += ", " + getEncoding() + " encoding, " + colorModel.toString();
        else
            msg += ", " + colorModel.toString();
        Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
    }

    private String getEncoding() {
        switch (preferredEncoding) {
            case RfbProto.EncodingRaw:
                return "RAW";
            case RfbProto.EncodingTight:
                return "TIGHT";
            case RfbProto.EncodingCoRRE:
                return "CoRRE";
            case RfbProto.EncodingHextile:
                return "HEXTILE";
            case RfbProto.EncodingRRE:
                return "RRE";
            case RfbProto.EncodingZlib:
                return "ZLIB";
            case RfbProto.EncodingZRLE:
                return "ZRLE";
        }
        return "";
    }

    private void reDraw() {
        if (repaintsEnabled)
            handler.post(reDraw);
    }

    final Paint handleCopyRectPaint = new Paint();
    private void handleCopyRect(int x, int y, int w, int h) throws IOException {

        /**
         * This does not work properly yet.
         */

        rfb.readCopyRect();
        if ( ! bitmapData.validDraw(x, y, w, h))
            return;
        // Source Coordinates
        int leftSrc = rfb.copyRectSrcX;
        int topSrc = rfb.copyRectSrcY;
        int rightSrc = topSrc + w;
        int bottomSrc = topSrc + h;

        // Change
        int dx = x - rfb.copyRectSrcX;
        int dy = y - rfb.copyRectSrcY;

        // Destination Coordinates
        int leftDest = leftSrc + dx;
        int topDest = topSrc + dy;
        int rightDest = rightSrc + dx;
        int bottomDest = bottomSrc + dy;

        bitmapData.copyRect(new Rect(leftSrc, topSrc, rightSrc, bottomSrc), new Rect(leftDest, topDest, rightDest, bottomDest), handleCopyRectPaint);

        reDraw();
    }

    byte[] bg_buf = new byte[4];
    byte[] rre_buf = new byte[128];
    //
    // Handle an RRE-encoded rectangle.
    //
    private void handleRRERect(int x, int y, int w, int h) throws IOException {
        boolean valid=bitmapData.validDraw(x, y, w, h);
        int nSubrects = rfb.is.readInt();

        rfb.readFully(bg_buf, 0, bytesPerPixel);
        int pixel;
        if (bytesPerPixel == 1) {
            pixel = colorPalette[0xFF & bg_buf[0]];
        } else {
            pixel = Color.rgb(bg_buf[2] & 0xFF, bg_buf[1] & 0xFF, bg_buf[0] & 0xFF);
        }
        handleRREPaint.setColor(pixel);
        if ( valid)
            bitmapData.drawRect(x, y, w, h, handleRREPaint);

        int len = nSubrects * (bytesPerPixel + 8);
        if (len > rre_buf.length)
            rre_buf = new byte[len];

        rfb.readFully(rre_buf, 0, len);
        if ( ! valid)
            return;

        int sx, sy, sw, sh;

        int i = 0;
        for (int j = 0; j < nSubrects; j++) {
            if (bytesPerPixel == 1) {
                pixel = colorPalette[0xFF & rre_buf[i++]];
            } else {
                pixel = Color.rgb(rre_buf[i + 2] & 0xFF, rre_buf[i + 1] & 0xFF, rre_buf[i] & 0xFF);
                i += 4;
            }
            sx = x + ((rre_buf[i] & 0xff) << 8) + (rre_buf[i+1] & 0xff); i+=2;
            sy = y + ((rre_buf[i] & 0xff) << 8) + (rre_buf[i+1] & 0xff); i+=2;
            sw = ((rre_buf[i] & 0xff) << 8) + (rre_buf[i+1] & 0xff); i+=2;
            sh = ((rre_buf[i] & 0xff) << 8) + (rre_buf[i+1] & 0xff); i+=2;

            handleRREPaint.setColor(pixel);
            bitmapData.drawRect(sx, sy, sw, sh, handleRREPaint);
        }

        reDraw();
    }

    private void handleCoRRERect(int x, int y, int w, int h) throws IOException {
        boolean valid=bitmapData.validDraw(x, y, w, h);
        int nSubrects = rfb.is.readInt();

        rfb.readFully(bg_buf, 0, bytesPerPixel);
        int pixel;
        if (bytesPerPixel == 1) {
            pixel = colorPalette[0xFF & bg_buf[0]];
        } else {
            pixel = Color.rgb(bg_buf[2] & 0xFF, bg_buf[1] & 0xFF, bg_buf[0] & 0xFF);
        }
        handleRREPaint.setColor(pixel);
        if ( valid)
            bitmapData.drawRect(x, y, w, h, handleRREPaint);

        int len = nSubrects * (bytesPerPixel + 8);
        if (len > rre_buf.length)
            rre_buf = new byte[len];

        rfb.readFully(rre_buf, 0, len);
        if ( ! valid)
            return;

        int sx, sy, sw, sh;
        int i = 0;

        for (int j = 0; j < nSubrects; j++) {
            if (bytesPerPixel == 1) {
                pixel = colorPalette[0xFF & rre_buf[i++]];
            } else {
                pixel = Color.rgb(rre_buf[i + 2] & 0xFF, rre_buf[i + 1] & 0xFF, rre_buf[i] & 0xFF);
                i += 4;
            }
            sx = x + (rre_buf[i++] & 0xFF);
            sy = y + (rre_buf[i++] & 0xFF);
            sw = rre_buf[i++] & 0xFF;
            sh = rre_buf[i++] & 0xFF;

            handleRREPaint.setColor(pixel);
            bitmapData.drawRect(sx, sy, sw, sh, handleRREPaint);
        }

        reDraw();
    }

    //
    // Handle a Hextile-encoded rectangle.
    //

    // These colors should be kept between handleHextileSubrect() calls.
    private int hextile_bg, hextile_fg;

    private void handleHextileRect(int x, int y, int w, int h) throws IOException {

        hextile_bg = Color.BLACK;
        hextile_fg = Color.BLACK;

        for (int ty = y; ty < y + h; ty += 16) {
            int th = 16;
            if (y + h - ty < 16)
                th = y + h - ty;

            for (int tx = x; tx < x + w; tx += 16) {
                int tw = 16;
                if (x + w - tx < 16)
                    tw = x + w - tx;

                handleHextileSubrect(tx, ty, tw, th);
            }

            // Finished with a row of tiles, now let's show it.
            reDraw();
        }
    }

    //
    // Handle one tile in the Hextile-encoded data.
    //

    Paint handleHextileSubrectPaint = new Paint();
    byte[] backgroundColorBuffer = new byte[4];
    private void handleHextileSubrect(int tx, int ty, int tw, int th) throws IOException {

        int subencoding = rfb.is.readUnsignedByte();

        // Is it a raw-encoded sub-rectangle?
        if ((subencoding & RfbProto.HextileRaw) != 0) {
            handleRawRect(tx, ty, tw, th, false);
            return;
        }

        boolean valid=bitmapData.validDraw(tx, ty, tw, th);
        // Read and draw the background if specified.
        if (bytesPerPixel > backgroundColorBuffer.length) {
            throw new RuntimeException("impossible colordepth");
        }
        if ((subencoding & RfbProto.HextileBackgroundSpecified) != 0) {
            rfb.readFully(backgroundColorBuffer, 0, bytesPerPixel);
            if (bytesPerPixel == 1) {
                hextile_bg = colorPalette[0xFF & backgroundColorBuffer[0]];
            } else {
                hextile_bg = Color.rgb(backgroundColorBuffer[2] & 0xFF, backgroundColorBuffer[1] & 0xFF, backgroundColorBuffer[0] & 0xFF);
            }
        }
        handleHextileSubrectPaint.setColor(hextile_bg);
        handleHextileSubrectPaint.setStyle(Paint.Style.FILL);
        if ( valid )
            bitmapData.drawRect(tx, ty, tw, th, handleHextileSubrectPaint);

        // Read the foreground color if specified.
        if ((subencoding & RfbProto.HextileForegroundSpecified) != 0) {
            rfb.readFully(backgroundColorBuffer, 0, bytesPerPixel);
            if (bytesPerPixel == 1) {
                hextile_fg = colorPalette[0xFF & backgroundColorBuffer[0]];
            } else {
                hextile_fg = Color.rgb(backgroundColorBuffer[2] & 0xFF, backgroundColorBuffer[1] & 0xFF, backgroundColorBuffer[0] & 0xFF);
            }
        }

        // Done with this tile if there is no sub-rectangles.
        if ((subencoding & RfbProto.HextileAnySubrects) == 0)
            return;

        int nSubrects = rfb.is.readUnsignedByte();
        int bufsize = nSubrects * 2;
        if ((subencoding & RfbProto.HextileSubrectsColoured) != 0) {
            bufsize += nSubrects * bytesPerPixel;
        }
        if (rre_buf.length < bufsize)
            rre_buf = new byte[bufsize];
        rfb.readFully(rre_buf, 0, bufsize);

        int b1, b2, sx, sy, sw, sh;
        int i = 0;
        if ((subencoding & RfbProto.HextileSubrectsColoured) == 0) {

            // Sub-rectangles are all of the same color.
            handleHextileSubrectPaint.setColor(hextile_fg);
            for (int j = 0; j < nSubrects; j++) {
                b1 = rre_buf[i++] & 0xFF;
                b2 = rre_buf[i++] & 0xFF;
                sx = tx + (b1 >> 4);
                sy = ty + (b1 & 0xf);
                sw = (b2 >> 4) + 1;
                sh = (b2 & 0xf) + 1;
                if ( valid)
                    bitmapData.drawRect(sx, sy, sw, sh, handleHextileSubrectPaint);
            }
        } else if (bytesPerPixel == 1) {

            // BGR233 (8-bit color) version for colored sub-rectangles.
            for (int j = 0; j < nSubrects; j++) {
                hextile_fg = colorPalette[0xFF & rre_buf[i++]];
                b1 = rre_buf[i++] & 0xFF;
                b2 = rre_buf[i++] & 0xFF;
                sx = tx + (b1 >> 4);
                sy = ty + (b1 & 0xf);
                sw = (b2 >> 4) + 1;
                sh = (b2 & 0xf) + 1;
                handleHextileSubrectPaint.setColor(hextile_fg);
                if ( valid)
                    bitmapData.drawRect(sx, sy, sw, sh, handleHextileSubrectPaint);
            }

        } else {

            // Full-color (24-bit) version for colored sub-rectangles.
            for (int j = 0; j < nSubrects; j++) {
                hextile_fg = Color.rgb(rre_buf[i + 2] & 0xFF, rre_buf[i + 1] & 0xFF, rre_buf[i] & 0xFF);
                i += 4;
                b1 = rre_buf[i++] & 0xFF;
                b2 = rre_buf[i++] & 0xFF;
                sx = tx + (b1 >> 4);
                sy = ty + (b1 & 0xf);
                sw = (b2 >> 4) + 1;
                sh = (b2 & 0xf) + 1;
                handleHextileSubrectPaint.setColor(hextile_fg);
                if ( valid )
                    bitmapData.drawRect(sx, sy, sw, sh, handleHextileSubrectPaint);
            }

        }
    }

    //
    // Handle a ZRLE-encoded rectangle.
    //

    Paint handleZRLERectPaint = new Paint();
    int[] handleZRLERectPalette = new int[128];
    private void handleZRLERect(int x, int y, int w, int h) throws Exception {

        if (zrleInStream == null)
            zrleInStream = new ZlibInStream();

        int nBytes = rfb.is.readInt();
        if (nBytes > 64 * 1024 * 1024)
            throw new Exception("ZRLE decoder: illegal compressed data size");

        if (zrleBuf == null || zrleBuf.length < nBytes) {
            zrleBuf = new byte[nBytes+4096];
        }

        rfb.readFully(zrleBuf, 0, nBytes);

        zrleInStream.setUnderlying(new MemInStream(zrleBuf, 0, nBytes), nBytes);

        boolean valid=bitmapData.validDraw(x, y, w, h);

        for (int ty = y; ty < y + h; ty += 64) {

            int th = Math.min(y + h - ty, 64);

            for (int tx = x; tx < x + w; tx += 64) {

                int tw = Math.min(x + w - tx, 64);

                int mode = zrleInStream.readU8();
                boolean rle = (mode & 128) != 0;
                int palSize = mode & 127;

                readZrlePalette(handleZRLERectPalette, palSize);

                if (palSize == 1) {
                    int pix = handleZRLERectPalette[0];
                    int c = (bytesPerPixel == 1) ? colorPalette[0xFF & pix] : (0xFF000000 | pix);
                    handleZRLERectPaint.setColor(c);
                    handleZRLERectPaint.setStyle(Paint.Style.FILL);
                    if ( valid)
                        bitmapData.drawRect(tx, ty, tw, th, handleZRLERectPaint);
                    continue;
                }

                if (!rle) {
                    if (palSize == 0) {
                        readZrleRawPixels(tw, th);
                    } else {
                        readZrlePackedPixels(tw, th, handleZRLERectPalette, palSize);
                    }
                } else {
                    if (palSize == 0) {
                        readZrlePlainRLEPixels(tw, th);
                    } else {
                        readZrlePackedRLEPixels(tw, th, handleZRLERectPalette);
                    }
                }
                if ( valid )
                    handleUpdatedZrleTile(tx, ty, tw, th);
            }
        }

        zrleInStream.reset();

        reDraw();
    }

    //
    // Handle a Zlib-encoded rectangle.
    //

    byte[] handleZlibRectBuffer = new byte[128];
    private void handleZlibRect(int x, int y, int w, int h) throws Exception {
        boolean valid = bitmapData.validDraw(x, y, w, h);
        int nBytes = rfb.is.readInt();

        if (zlibBuf == null || zlibBuf.length < nBytes) {
            zlibBuf = new byte[nBytes*2];
        }

        rfb.readFully(zlibBuf, 0, nBytes);

        if (zlibInflater == null) {
            zlibInflater = new Inflater();
        }
        zlibInflater.setInput(zlibBuf, 0, nBytes);

        int[] pixels=bitmapData.bitmapPixels;

        if (bytesPerPixel == 1) {
            // 1 byte per pixel. Use palette lookup table.
            if (w > handleZlibRectBuffer.length) {
                handleZlibRectBuffer = new byte[w];
            }
            int i, offset;
            for (int dy = y; dy < y + h; dy++) {
                zlibInflater.inflate(handleZlibRectBuffer,  0, w);
                if ( ! valid)
                    continue;
                offset = bitmapData.offset(x, dy);
                for (i = 0; i < w; i++) {
                    pixels[offset + i] = colorPalette[0xFF & handleZlibRectBuffer[i]];
                }
            }
        } else {
            // 24-bit color (ARGB) 4 bytes per pixel.
            final int l = w*4;
            if (l > handleZlibRectBuffer.length) {
                handleZlibRectBuffer = new byte[l];
            }
            int i, offset;
            for (int dy = y; dy < y + h; dy++) {
                zlibInflater.inflate(handleZlibRectBuffer, 0, l);
                if ( ! valid)
                    continue;
                offset = bitmapData.offset(x, dy);
                for (i = 0; i < w; i++) {
                    final int idx = i*4;
                    pixels[offset + i] = (handleZlibRectBuffer[idx + 2] & 0xFF) << 16 | (handleZlibRectBuffer[idx + 1] & 0xFF) << 8 | (handleZlibRectBuffer[idx] & 0xFF);
                }
            }
        }
        if ( ! valid)
            return;
        bitmapData.updateBitmap(x, y, w, h);

        reDraw();
    }

    private void readZrlePalette(int[] palette, int palSize) throws Exception {
        readPixels(zrleInStream, palette, palSize);
    }

    private void readZrleRawPixels(int tw, int th) throws Exception {
        int len = tw * th;
        if (zrleTilePixels == null || len > zrleTilePixels.length)
            zrleTilePixels = new int[len];
        readPixels(zrleInStream, zrleTilePixels, tw * th); // /
    }

    private void readZrlePackedPixels(int tw, int th, int[] palette, int palSize) throws Exception {

        int bppp = ((palSize > 16) ? 8 : ((palSize > 4) ? 4 : ((palSize > 2) ? 2 : 1)));
        int ptr = 0;
        int len = tw * th;
        if (zrleTilePixels == null || len > zrleTilePixels.length)
            zrleTilePixels = new int[len];

        for (int i = 0; i < th; i++) {
            int eol = ptr + tw;
            int b = 0;
            int nbits = 0;

            while (ptr < eol) {
                if (nbits == 0) {
                    b = zrleInStream.readU8();
                    nbits = 8;
                }
                nbits -= bppp;
                int index = (b >> nbits) & ((1 << bppp) - 1) & 127;
                if (bytesPerPixel == 1) {
                    if (index >= colorPalette.length)
                        Log.e(TAG, "zrlePlainRLEPixels palette lookup out of bounds " + index + " (0x" + Integer.toHexString(index) + ")");
                    zrleTilePixels[ptr++] = colorPalette[0xFF & palette[index]];
                } else {
                    zrleTilePixels[ptr++] = palette[index];
                }
            }
        }
    }

    private void readZrlePlainRLEPixels(int tw, int th) throws Exception {
        int ptr = 0;
        int end = ptr + tw * th;
        if (zrleTilePixels == null || end > zrleTilePixels.length)
            zrleTilePixels = new int[end];
        while (ptr < end) {
            int pix = readPixel(zrleInStream);
            int len = 1;
            int b;
            do {
                b = zrleInStream.readU8();
                len += b;
            } while (b == 255);

            if (!(len <= end - ptr))
                throw new Exception("ZRLE decoder: assertion failed" + " (len <= end-ptr)");

            if (bytesPerPixel == 1) {
                while (len-- > 0)
                    zrleTilePixels[ptr++] = colorPalette[0xFF & pix];
            } else {
                while (len-- > 0)
                    zrleTilePixels[ptr++] = pix;
            }
        }
    }

    private void readZrlePackedRLEPixels(int tw, int th, int[] palette) throws Exception {

        int ptr = 0;
        int end = ptr + tw * th;
        if (zrleTilePixels == null || end > zrleTilePixels.length)
            zrleTilePixels = new int[end];
        while (ptr < end) {
            int index = zrleInStream.readU8();
            int len = 1;
            if ((index & 128) != 0) {
                int b;
                do {
                    b = zrleInStream.readU8();
                    len += b;
                } while (b == 255);

                if (!(len <= end - ptr))
                    throw new Exception("ZRLE decoder: assertion failed" + " (len <= end - ptr)");
            }

            index &= 127;
            int pix = palette[index];

            if (bytesPerPixel == 1) {
                while (len-- > 0)
                    zrleTilePixels[ptr++] = colorPalette[0xFF & pix];
            } else {
                while (len-- > 0)
                    zrleTilePixels[ptr++] = pix;
            }
        }
    }

    //
    // Copy pixels from zrleTilePixels8 or zrleTilePixels24, then update.
    //

    private void handleUpdatedZrleTile(int x, int y, int w, int h) {
        int offsetSrc = 0;
        int[] destPixels=bitmapData.bitmapPixels;
        for (int j = 0; j < h; j++) {
            System.arraycopy(zrleTilePixels, offsetSrc, destPixels, bitmapData.offset(x, y + j), w);
            offsetSrc += w;
        }

        bitmapData.updateBitmap(x, y, w, h);
    }

    byte[] readPixelsBuffer = new byte[128];
    private void readPixels(InStream is, int[] dst, int count) throws Exception {
        if (bytesPerPixel == 1) {
            if (count > readPixelsBuffer.length) {
                readPixelsBuffer = new byte[count];
            }
            is.readBytes(readPixelsBuffer, 0, count);
            for (int i = 0; i < count; i++) {
                dst[i] = (int) readPixelsBuffer[i] & 0xFF;
            }
        } else {
            final int l = count * 3;
            if (l > readPixelsBuffer.length) {
                readPixelsBuffer = new byte[l];
            }
            is.readBytes(readPixelsBuffer, 0, l);
            for (int i = 0; i < count; i++) {
                final int idx = i*3;
                dst[i] = ((readPixelsBuffer[idx + 2] & 0xFF) << 16 | (readPixelsBuffer[idx + 1] & 0xFF) << 8 | (readPixelsBuffer[idx] & 0xFF));
            }
        }
    }

    private int readPixel(InStream is) throws Exception {
        int pix;
        if (bytesPerPixel == 1) {
            pix = is.readU8();
        } else {
            int p1 = is.readU8();
            int p2 = is.readU8();
            int p3 = is.readU8();
            pix = (p3 & 0xFF) << 16 | (p2 & 0xFF) << 8 | (p1 & 0xFF);
        }
        return pix;
    }

    /**
     * Additional Encodings
     *
     */

    private void setEncodings(boolean autoSelectOnly) {
        if (rfb == null || !rfb.inNormalProtocol)
            return;

        if (preferredEncoding == -1) {
            // Preferred format is ZRLE
            preferredEncoding = RfbProto.EncodingZRLE;
        } else {
            // Auto encoder selection is not enabled.
            if (autoSelectOnly)
                return;
        }

        int[] encodings = new int[20];
        int nEncodings = 0;

        encodings[nEncodings++] = preferredEncoding;
        if (useCopyRect)
            encodings[nEncodings++] = RfbProto.EncodingCopyRect;
        // if (preferredEncoding != RfbProto.EncodingTight)
        // encodings[nEncodings++] = RfbProto.EncodingTight;
        if (preferredEncoding != RfbProto.EncodingZRLE)
            encodings[nEncodings++] = RfbProto.EncodingZRLE;
        if (preferredEncoding != RfbProto.EncodingHextile)
            encodings[nEncodings++] = RfbProto.EncodingHextile;
        if (preferredEncoding != RfbProto.EncodingZlib)
            encodings[nEncodings++] = RfbProto.EncodingZlib;
        if (preferredEncoding != RfbProto.EncodingCoRRE)
            encodings[nEncodings++] = RfbProto.EncodingCoRRE;
        if (preferredEncoding != RfbProto.EncodingRRE)
            encodings[nEncodings++] = RfbProto.EncodingRRE;

        if (compressLevel >= 0 && compressLevel <= 9)
            encodings[nEncodings++] = RfbProto.EncodingCompressLevel0 + compressLevel;
        if (jpegQuality >= 0 && jpegQuality <= 9)
            encodings[nEncodings++] = RfbProto.EncodingQualityLevel0 + jpegQuality;

        if (requestCursorUpdates) {
            encodings[nEncodings++] = RfbProto.EncodingXCursor;
            encodings[nEncodings++] = RfbProto.EncodingRichCursor;
            if (!ignoreCursorUpdates)
                encodings[nEncodings++] = RfbProto.EncodingPointerPos;
        }

        encodings[nEncodings++] = RfbProto.EncodingLastRect;
        encodings[nEncodings++] = RfbProto.EncodingNewFBSize;

        boolean encodingsWereChanged = false;
        if (nEncodings != nEncodingsSaved) {
            encodingsWereChanged = true;
        } else {
            for (int i = 0; i < nEncodings; i++) {
                if (encodings[i] != encodingsSaved[i]) {
                    encodingsWereChanged = true;
                    break;
                }
            }
        }

        if (encodingsWereChanged) {
            try {
                rfb.writeSetEncodings(encodings, nEncodings);
            } catch (Exception e) {
                e.printStackTrace();
            }
            encodingsSaved = encodings;
            nEncodingsSaved = nEncodings;
        }
    }

    @Override
    protected void onDraw( Canvas canvas ) {
        Canvas glAttachedCanvas = m_ViewRenderer.onDrawViewBegin();
        if(glAttachedCanvas != null) {
            glAttachedCanvas.translate(-getScrollX(), -getScrollY());
            super.onDraw(glAttachedCanvas);
        }
        m_ViewRenderer.onDrawViewEnd();
    }


}

