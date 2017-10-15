package forpdateam.ru.forpda;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.ArraySet;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.util.SparseArray;

import com.nostra13.universalimageloader.core.ImageLoader;

import org.acra.ACRA;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Observer;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;

import forpdateam.ru.forpda.api.Api;
import forpdateam.ru.forpda.api.Utils;
import forpdateam.ru.forpda.api.events.NotificationEvents;
import forpdateam.ru.forpda.api.events.models.NotificationEvent;
import forpdateam.ru.forpda.api.others.user.ForumUser;
import forpdateam.ru.forpda.client.Client;
import forpdateam.ru.forpda.client.ClientHelper;
import forpdateam.ru.forpda.data.models.TabNotification;
import forpdateam.ru.forpda.rxapi.ForumUsersCache;
import forpdateam.ru.forpda.settings.Preferences;
import forpdateam.ru.forpda.utils.BitmapUtils;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Created by radiationx on 31.07.17.
 */

public class NotificationsService extends Service {
    private final static String LOG_TAG = NotificationsService.class.getSimpleName();
    public final static String CHECK_LAST_EVENTS = "CHECK_LAST_EVENTS";
    private final static int NOTIFY_STACKED_QMS_ID = -123;
    private final static int NOTIFY_STACKED_FAV_ID = -234;
    private Handler wsHandler = new Handler(Looper.getMainLooper());
    private NotificationManagerCompat mNotificationManager;
    private SparseArray<NotificationEvent> eventsHistory = new SparseArray<>();
    private WebSocket webSocket;
    private boolean connected = false;
    private long lastHardCheckTime = 0;
    private long timerPeriod = Preferences.Notifications.Main.getLimit();
    private HashMap<NotificationEvent.Source, SparseArray<NotificationEvent>> pendingEvents = new HashMap<>(3);
    private Timer checkTimer;
    private Runnable timerRunnable = () -> {
        Log.d(LOG_TAG, "Call timer runnable");
        /*int count = 0;
        for (NotificationEvent.Source source : pendingEvents.keySet()) {
            SparseArray<NotificationEvent> pending = pendingEvents.get(source);
            count += pending.size();
        }

        if (count == 0) {
            checkTimer.cancel();
            checkTimer.purge();
            return;
        }*/

        for (NotificationEvent.Source source : pendingEvents.keySet()) {
            handlePendingEvents(source);
        }
    };

    private Observer loginObserver = (observable, o) -> {
        if (o == null) o = false;

    };
    private Observer networkObserver = (observable, o) -> {
        if (o == null) o = true;
        if ((boolean) o) {
            /*if (Preferences.Notifications.Main.isEnabled()) {
                start(true);
            }*/
            start(true);
        }
    };

    private Observer notificationSettingObserver = (observable, o) -> {
        if (o == null) return;
        String key = (String) o;
        switch (key) {
            case Preferences.Notifications.Main.ENABLED: {
                /*if (Preferences.Notifications.Main.isEnabled()) {
                    start(true);
                } else {
                    stop();
                }*/
                break;
            }
            case Preferences.Notifications.Main.LIMIT: {
                timerPeriod = Preferences.Notifications.Main.getLimit();
                Log.d(LOG_TAG, "NEW timer period " + timerPeriod);
                resetTimer();
                break;
            }
            case Preferences.Notifications.Favorites.ENABLED: {
                if (Preferences.Notifications.Favorites.isEnabled()) {
                    hardHandleEvent(NotificationEvent.Source.THEME);
                }
                break;
            }
            case Preferences.Notifications.Qms.ENABLED: {
                if (Preferences.Notifications.Qms.isEnabled()) {
                    hardHandleEvent(NotificationEvent.Source.QMS);
                }
                break;
            }

            /*case Preferences.Notifications.Mentions.ENABLED: {

                break;
            }*/
        }
    };

    private WebSocketListener webSocketListener = new WebSocketListener() {
        Matcher matcher = null;

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(LOG_TAG, "WSListener onOpen: " + response.toString());
            connected = true;
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(LOG_TAG, "WSListener onMessage: " + text);
            if (matcher == null) {
                matcher = NotificationEvents.webSocketEventPattern.matcher(text);
            } else {
                matcher = matcher.reset(text);
            }
            NotificationEvent event = Api.UniversalEvents().parseWebSocketEvent(matcher);
            try {
                if (event != null) {
                    if (event.getType() != NotificationEvent.Type.HAT_EDITED) {
                        wsHandler.post(() -> handleWebSocketEvent(event));
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(LOG_TAG, "WSListener onClosed: " + code + " " + reason);
            //stop();
            wsHandler.post(() -> stop());
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.d(LOG_TAG, "WSListener onFailure: " + t.getMessage() + " " + response);
            t.printStackTrace();
            //stop();
            wsHandler.post(() -> stop());
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.i(LOG_TAG, "onCreate");
        Client.get().addNetworkObserver(networkObserver);
        App.get().addPreferenceChangeObserver(notificationSettingObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(LOG_TAG, "onStartCommand args" + flags + " : " + startId + " : " + intent);
        Log.i(LOG_TAG, "onStartCommand websocket" + webSocket);
        if (mNotificationManager == null) {
            mNotificationManager = NotificationManagerCompat.from(this);
        }
        if (pendingEvents.isEmpty()) {
            pendingEvents.put(NotificationEvent.Source.QMS, new SparseArray<>());
            pendingEvents.put(NotificationEvent.Source.THEME, new SparseArray<>());
            pendingEvents.put(NotificationEvent.Source.SITE, new SparseArray<>());
        }
        //if (Preferences.Notifications.Main.isEnabled()) {
        boolean checkEvents = intent != null && intent.getAction() != null && intent.getAction().equals(CHECK_LAST_EVENTS);
        long time = System.currentTimeMillis();

        Log.d(LOG_TAG, "Handle check last events: " + time + " : " + lastHardCheckTime + " : " + (time - lastHardCheckTime));

        if (checkEvents && ((time - lastHardCheckTime) >= 1000 * 60 * 1)) {
            lastHardCheckTime = time;
            checkEvents = true;
        } else {
            checkEvents = false;
        }
        start(checkEvents);
        //}
        return START_STICKY;
    }

    private void cancelTimer() {
        if (checkTimer != null) {
            checkTimer.cancel();
            checkTimer.purge();
            checkTimer = null;
        }
    }

    private void resetTimer() {
        cancelTimer();
        checkTimer = new Timer();
        checkTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                timerRunnable.run();
            }
        }, 0, timerPeriod);
    }

    private void start(boolean checkEvents) {
        if (Client.get().getNetworkState()) {
            if (!connected) {
                webSocket = Client.get().createWebSocketConnection(webSocketListener);
            }
            webSocket.send("[0,\"sv\"]");
            webSocket.send("[0, \"ea\", \"u" + ClientHelper.getUserId() + "\"]");
            if (checkEvents) {
                hardHandleEvent(NotificationEvent.Source.THEME);
                hardHandleEvent(NotificationEvent.Source.QMS);
            }
            Log.d("SUKA", "PERIOD BLYAD " + timerPeriod);

            resetTimer();
        }
    }

    private void stop() {
        connected = false;
        if (webSocket != null) {
            webSocket.cancel();
        }
        cancelTimer();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(LOG_TAG, "onDestroy");
        App.get().removePreferenceChangeObserver(notificationSettingObserver);
        Client.get().removeNetworkObserver(networkObserver);
        stop();
    }


    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(LOG_TAG, "onTaskRemoved");
        stop();
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT) {
            Intent restartIntent = new Intent(this, getClass());

            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getService(this, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT);
            restartIntent.putExtra("RESTART", "RESTART_CHEBUREK");
            am.setExact(AlarmManager.RTC, System.currentTimeMillis() + 3000, pi);
        }
    }

    private void handleWebSocketEvent(NotificationEvent event) {
        TabNotification tabNotification = new TabNotification();
        tabNotification.setType(event.getType());
        tabNotification.setSource(event.getSource());
        tabNotification.setEvent(event);
        tabNotification.setWebSocket(true);
        notifyTabs(tabNotification);

        if (event.isRead()) {
            NotificationEvent oldEvent = eventsHistory.get(event.notifyId(NotificationEvent.Type.NEW));
            boolean delete = false;

            if (event.fromTheme()) {
                //Убираем уведомления избранного
                if (oldEvent != null && event.getMessageId() >= oldEvent.getMessageId()) {
                    mNotificationManager.cancel(oldEvent.notifyId());
                    delete = true;
                }

                //Убираем уведомление упоминаний
                oldEvent = eventsHistory.get(event.notifyId(NotificationEvent.Type.MENTION));
                if (oldEvent != null) {
                    mNotificationManager.cancel(oldEvent.notifyId());
                    delete = true;
                }
            } else if (event.fromQms()) {

                //Убираем уведомление кумыса
                if (oldEvent != null) {
                    mNotificationManager.cancel(oldEvent.notifyId());
                    delete = true;
                }
            }

            if (delete) {
                eventsHistory.remove(event.notifyId(NotificationEvent.Type.NEW));
            }
            return;
        }
        List<NotificationEvent> events = new ArrayList<>();
        events.add(event);
        handleEvent(events, event.getSource());
    }

    private boolean checkNotify(@Nullable NotificationEvent event, NotificationEvent.Source source) {
        if (!Preferences.Notifications.Main.isEnabled()) {
            return false;
        }
        if (NotificationEvent.fromQms(source)) {
            if (!Preferences.Notifications.Qms.isEnabled()) {
                return false;
            }
        } else if (NotificationEvent.fromTheme(source)) {
            if (event != null && event.isMention()) {
                if (!Preferences.Notifications.Mentions.isEnabled()) {
                    return false;
                }
            } else {
                if (!Preferences.Notifications.Favorites.isEnabled()) {
                    return false;
                }
            }
        }
        return true;
    }


    private void handleEvent(List<NotificationEvent> events, NotificationEvent.Source source) {
        SparseArray<NotificationEvent> pending = pendingEvents.get(source);
        if (pending != null) {
            for (NotificationEvent event : events) {
                pending.put(event.getSourceId(), event);
            }
        }
    }

    private void hardHandleEvent(NotificationEvent.Source source) {
        hardHandleEvent(new ArrayList<>(), source);
    }

    private void hardHandleEvent(List<NotificationEvent> events, NotificationEvent.Source source) {
        Log.d("SUKA", "hardHandleEvent " + events.size() + " : " + source);
        if (NotificationEvent.fromSite(source)) {
            if (Preferences.Notifications.Mentions.isEnabled()) {
                for (NotificationEvent event : events) {
                    sendNotification(event);
                }
            }
            return;
        }
        loadEvents(loadedEvents -> {
            List<NotificationEvent> savedEvents = getSavedEvents(source);
            //savedEvents = new ArrayList<>();
            saveEvents(loadedEvents, source);
            List<NotificationEvent> newEvents = compareEvents(savedEvents, loadedEvents, events, source);
            List<NotificationEvent> stackedNewEvents = new ArrayList<>(newEvents);

            //Удаляем из общего уведомления текущие уведомление
            for (NotificationEvent event : events) {
                for (NotificationEvent newEvent : newEvents) {
                    if (newEvent.getSourceId() == event.getSourceId()) {
                        stackedNewEvents.remove(newEvent);
                        newEvent.setType(event.getType());
                        newEvent.setMessageId(event.getMessageId());

                        TabNotification tabNotification = new TabNotification();
                        tabNotification.setType(newEvent.getType());
                        tabNotification.setSource(newEvent.getSource());
                        tabNotification.setEvent(newEvent);
                        tabNotification.getLoadedEvents().addAll(loadedEvents);
                        tabNotification.getNewEvents().addAll(newEvents);
                        tabNotification.setWebSocket(false);
                        notifyTabs(tabNotification);

                        sendNotification(newEvent);
                    } else if (event.isMention() && !Preferences.Notifications.Favorites.isEnabled()) {
                        stackedNewEvents.remove(newEvent);
                    }
                }
            }

            sendNotifications(stackedNewEvents, source);
        }, source);
    }

    private void handlePendingEvents(NotificationEvent.Source source) {
        SparseArray<NotificationEvent> pending = pendingEvents.get(source);
        if (pending != null && pending.size() > 0) {
            List<NotificationEvent> events = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                events.add(pending.valueAt(i));
            }
            hardHandleEvent(events, source);
            pending.clear();
        }
    }


    private List<NotificationEvent> getSavedEvents(NotificationEvent.Source source) {
        String prefKey = "";
        if (NotificationEvent.fromQms(source)) {
            prefKey = Preferences.Notifications.Data.QMS_EVENTS;
        } else if (NotificationEvent.fromTheme(source)) {
            prefKey = Preferences.Notifications.Data.FAVORITES_EVENTS;
        }

        Set<String> savedEvents = App.get().getPreferences().getStringSet(prefKey, new ArraySet<>());
        StringBuilder responseBuilder = new StringBuilder();
        for (String saved : savedEvents) {
            responseBuilder.append(saved).append('\n');
        }
        String response = responseBuilder.toString();

        if (NotificationEvent.fromQms(source)) {
            return Api.UniversalEvents().getQmsEvents(response);
        } else if (NotificationEvent.fromTheme(source)) {
            return Api.UniversalEvents().getFavoritesEvents(response);
        }
        return new ArrayList<>();
    }

    private void saveEvents(List<NotificationEvent> loadedEvents, NotificationEvent.Source source) {
        String prefKey = "";
        if (NotificationEvent.fromQms(source)) {
            prefKey = Preferences.Notifications.Data.QMS_EVENTS;
        } else if (NotificationEvent.fromTheme(source)) {
            prefKey = Preferences.Notifications.Data.FAVORITES_EVENTS;
        }

        Set<String> savedEvents = new ArraySet<>();
        for (NotificationEvent event : loadedEvents) {
            savedEvents.add(event.getSourceEventText());
        }
        App.get().getPreferences().edit().putStringSet(prefKey, savedEvents).apply();
    }

    private List<NotificationEvent> compareEvents(List<NotificationEvent> savedEvents,
                                                  List<NotificationEvent> loadedEvents,
                                                  List<NotificationEvent> events,
                                                  NotificationEvent.Source source) {
        List<NotificationEvent> newEvents = new ArrayList<>();

        for (NotificationEvent loaded : loadedEvents) {
            boolean isNew = true;
            for (NotificationEvent saved : savedEvents) {
                if (loaded.getSourceId() == saved.getSourceId() && loaded.getTimeStamp() <= saved.getTimeStamp()) {
                    isNew = false;
                    break;
                }
            }

            if (isNew) {
                newEvents.add(loaded);
            }
        }

        if (NotificationEvent.fromTheme(source) && Preferences.Notifications.Favorites.isOnlyImportant()) {
            List<NotificationEvent> toRemove = new ArrayList<>();
            for (NotificationEvent newEvent : newEvents) {
                boolean remove = false;
                for (NotificationEvent event : events) {
                    if (!event.isMention() && !newEvent.isImportant()) {
                        remove = true;
                        break;
                    }
                }
                if (!newEvent.isImportant()) {
                    remove = true;
                }
                if (remove) {
                    toRemove.add(newEvent);
                }
            }
            for (NotificationEvent removeEvent : toRemove) {
                newEvents.remove(removeEvent);
            }
            toRemove.clear();
        }

        return newEvents;
    }

    private void loadEvents(Consumer<List<NotificationEvent>> consumer, NotificationEvent.Source source) {
        Observable<List<NotificationEvent>> observable = null;
        if (NotificationEvent.fromQms(source)) {
            observable = Observable.fromCallable(() -> Api.UniversalEvents().getQmsEvents());
        } else if (NotificationEvent.fromTheme(source)) {
            observable = Observable.fromCallable(() -> Api.UniversalEvents().getFavoritesEvents());
        }

        if (observable != null) {
            observable
                    .onErrorReturnItem(new ArrayList<>())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(consumer);
        }
    }

    public Bitmap loadAvatar(NotificationEvent event) throws Exception {
        Bitmap bitmap = null;
        if (!event.fromSite()) {
            ForumUser forumUser = ForumUsersCache.getUserById(event.getUserId());
            Log.d(LOG_TAG, "Forum user from cache " + forumUser);
            if (forumUser == null) {
                forumUser = ForumUsersCache.loadUserByNick(event.getUserNick());
                Log.d(LOG_TAG, "Forum user from network " + forumUser);
            }

            if (forumUser != null) {
                bitmap = ImageLoader.getInstance().loadImageSync(forumUser.getAvatar());
                Log.d(LOG_TAG, "Loaded avatar bitmap" + bitmap);
                if (bitmap != null) {
                    Log.d(LOG_TAG, "Bitmap h/w: " + bitmap.getHeight() + " : " + bitmap.getWidth());
                }
            }
        }

        return bitmap;
    }

    public void notifyTabs(TabNotification event) {
        Log.d("SUKA", "notifyTabs");
        try {
            switch (event.getSource()) {
                case THEME:
                    App.get().notifyFavorites(event);
                    break;
                case QMS:
                    App.get().notifyQms(event);
            }
        } catch (Exception ex) {
            ACRA.getErrorReporter().handleException(ex);
        }
    }

    private void configureNotification(NotificationCompat.Builder builder) {
        builder.setAutoCancel(true);
        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        builder.setCategory(NotificationCompat.CATEGORY_SOCIAL);
        int defaults = 0;
        if (Preferences.Notifications.Main.isSoundEnabled()) {
            defaults |= NotificationCompat.DEFAULT_SOUND;
        }
        if (Preferences.Notifications.Main.isVibrationEnabled()) {
            defaults |= NotificationCompat.DEFAULT_VIBRATE;
        }
        if (Preferences.Notifications.Main.isIndicatorEnabled()) {
            defaults |= NotificationCompat.DEFAULT_LIGHTS;
        }
        builder.setDefaults(defaults);
        builder.setVibrate(new long[]{0L});
    }

    public void sendNotification(NotificationEvent event, Bitmap avatar) {
        eventsHistory.put(event.notifyId(), event);


        String title = createTitle(event);
        String text = createContent(event);
        String summaryText = createSummary(event);

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
        bigTextStyle.setBigContentTitle(title);
        bigTextStyle.bigText(text);
        bigTextStyle.setSummaryText(summaryText);

        NotificationCompat.Builder builder;
        builder = new NotificationCompat.Builder(this);

        if (avatar != null && !event.fromSite()) {
            builder.setLargeIcon(avatar);
        }
        builder.setSmallIcon(createSmallIcon(event));

        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setStyle(bigTextStyle);


        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setData(Uri.parse(createIntentUrl(event)));
        notifyIntent.setAction(Intent.ACTION_VIEW);
        PendingIntent notifyPendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0);
        builder.setContentIntent(notifyPendingIntent);

        configureNotification(builder);

        mNotificationManager.cancel(event.notifyId());
        mNotificationManager.notify(event.notifyId(), builder.build());
    }

    public void sendNotification(NotificationEvent event) {
        if (event.getUserId() == ClientHelper.getUserId()) {
            return;
        }

        if (!checkNotify(event, event.getSource())) {
            return;
        }

        if (Preferences.Notifications.Main.isAvatarsEnabled()) {
            Observable.fromCallable(() -> loadAvatar(event))
                    .onErrorReturn(throwable -> ImageLoader.getInstance().loadImageSync("assets://av.png"))
                    .map(bitmap -> {
                        if (bitmap != null) {
                            Resources res = App.getContext().getResources();
                            int height = (int) res.getDimension(android.R.dimen.notification_large_icon_height);
                            int width = (int) res.getDimension(android.R.dimen.notification_large_icon_width);
                            boolean isCircle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;

                            bitmap = BitmapUtils.centerCrop(bitmap, width, height, 1.0f);
                            bitmap = BitmapUtils.createAvatar(bitmap, width, height, isCircle);
                        }
                        return bitmap;
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(avatar -> sendNotification(event, avatar));
        } else {
            sendNotification(event, null);
        }
    }


    public void sendNotifications(List<NotificationEvent> events, NotificationEvent.Source tSource) {
        if (events.isEmpty()) {
            return;
        }
        if (events.size() == 1) {
            sendNotification(events.get(0));
            return;
        }
        if (!checkNotify(null, tSource)) {
            return;
        }

        String title = createStackedTitle(events);
        CharSequence text = createStackedContent(events);
        String summaryText = createStackedSummary(events);

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
        bigTextStyle.setBigContentTitle(title);
        bigTextStyle.bigText(text);
        bigTextStyle.setSummaryText(summaryText);

        NotificationCompat.Builder builder;
        builder = new NotificationCompat.Builder(this);

        builder.setSmallIcon(createStackedSmallIcon(events));

        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setStyle(bigTextStyle);

        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setData(Uri.parse(createStackedIntentUrl(events)));
        notifyIntent.setAction(Intent.ACTION_VIEW);
        PendingIntent notifyPendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0);
        builder.setContentIntent(notifyPendingIntent);

        configureNotification(builder);

        int id = 0;
        NotificationEvent event = events.get(0);
        if (event.fromQms()) {
            id = NOTIFY_STACKED_QMS_ID;
        } else if (event.fromTheme()) {
            id = NOTIFY_STACKED_FAV_ID;
        }
        mNotificationManager.notify(id, builder.build());
    }


    /*
    * DEFAULT EVENT
    * */

    @DrawableRes
    public int createSmallIcon(NotificationEvent event) {
        if (event.fromQms())
            return R.drawable.ic_notify_qms;

        if (event.fromTheme()) {
            if (event.isMention())
                return R.drawable.ic_notify_mention;

            return R.drawable.ic_notify_favorites;
        }

        if (event.fromSite())
            return R.drawable.ic_notify_site;

        return R.drawable.ic_notify_qms;
    }

    public String createTitle(NotificationEvent event) {
        if (event.fromQms()) {
            String nick = event.getUserNick();
            if (nick == null || nick.isEmpty())
                return "Сообщения 4PDA";
        }

        if (event.fromSite())
            return "ForPDA";

        return event.getUserNick();
    }

    public String createContent(NotificationEvent event) {
        if (event.fromQms())
            return String.format(getString(R.string.notification_content_qms_Nick_Count), event.getSourceTitle(), event.getMsgCount());

        if (event.fromTheme()) {
            if (event.isMention())
                return String.format(getString(R.string.notification_content_mention_Title), event.getSourceTitle());

            return String.format(getString(R.string.notification_content_theme_Title), event.getSourceTitle());
        }

        if (event.fromSite())
            return getString(R.string.notification_content_news);

        return "";
    }

    public String createSummary(NotificationEvent event) {
        if (event.isMention())
            return getString(R.string.notification_summary_mention);

        if (event.fromQms())
            return getString(R.string.notification_summary_qms);

        if (event.fromTheme())
            return getString(R.string.notification_summary_fav);

        if (event.fromSite())
            return getString(R.string.notification_summary_comment);

        return "";
    }

    public String createIntentUrl(NotificationEvent event) {
        if (event.isMention()) {
            if (event.fromTheme())
                return "https://4pda.ru/forum/index.php?showtopic=" + event.getSourceId() + "&view=findpost&p=" + event.getMessageId();

            if (event.fromSite())
                return "https://4pda.ru/index.php?p=" + event.getSourceId() + "/#comment" + event.getMessageId();
        }

        if (event.fromQms())
            return "https://4pda.ru/forum/index.php?act=qms&mid=" + event.getUserId() + "&t=" + event.getSourceId();

        if (event.fromTheme())
            return "https://4pda.ru/forum/index.php?showtopic=" + event.getSourceId() + "&view=getnewpost";

        return "";
    }


    /*
    * STACKED EVENTS
    * */
    private String createStackedTitle(List<NotificationEvent> events) {
        return createStackedSummary(events);
    }

    private CharSequence createStackedContent(List<NotificationEvent> events) {
        StringBuilder content = new StringBuilder();

        final int maxCount = 4;
        int size = Math.min(events.size(), maxCount);
        for (int i = 0; i < size; i++) {
            NotificationEvent event = events.get(i);
            if (event.fromQms()) {
                content.append("<b>").append(event.getUserNick()).append("</b>");
                content.append(": ").append(event.getSourceTitle());
            } else if (event.fromTheme()) {
                content.append(event.getSourceTitle());
            }
            if (i < size - 1) {
                content.append("<br>");
            }
        }

        if (events.size() > size) {
            content.append("<br>");
            content.append("...и еще ").append(events.size() - size);
        }

        return Utils.spannedFromHtml(content.toString());
    }

    private String createStackedSummary(List<NotificationEvent> events) {
        return createSummary(events.get(0));
    }

    @DrawableRes
    public int createStackedSmallIcon(List<NotificationEvent> events) {
        return createSmallIcon(events.get(0));
    }

    private String createStackedIntentUrl(List<NotificationEvent> events) {
        NotificationEvent event = events.get(0);
        if (event.fromQms())
            return "https://4pda.ru/forum/index.php?act=qms";

        if (event.fromTheme())
            return "https://4pda.ru/forum/index.php?act=fav";

        return "";
    }
}
