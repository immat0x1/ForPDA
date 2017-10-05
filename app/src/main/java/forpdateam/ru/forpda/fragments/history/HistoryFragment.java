package forpdateam.ru.forpda.fragments.history;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import forpdateam.ru.forpda.App;
import forpdateam.ru.forpda.R;
import forpdateam.ru.forpda.TabManager;
import forpdateam.ru.forpda.data.realm.history.HistoryItemBd;
import forpdateam.ru.forpda.fragments.RecyclerFragment;
import forpdateam.ru.forpda.fragments.TabFragment;
import forpdateam.ru.forpda.fragments.history.adapters.HistoryAdapter;
import forpdateam.ru.forpda.utils.AlertDialogMenu;
import forpdateam.ru.forpda.utils.IntentHandler;
import forpdateam.ru.forpda.utils.Utils;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;

/**
 * Created by radiationx on 06.09.17.
 */

public class HistoryFragment extends RecyclerFragment implements HistoryAdapter.OnItemClickListener<HistoryItemBd> {
    private final static SimpleDateFormat dateFormat = new SimpleDateFormat("MM.dd.yy, HH:mm", Locale.getDefault());
    private HistoryAdapter adapter;
    private Realm realm;
    private AlertDialogMenu<HistoryFragment, HistoryItemBd> dialogMenu, showedDialogMenu;

    public HistoryFragment() {
        configuration.setUseCache(true);
        configuration.setDefaultTitle(App.get().getString(R.string.fragment_title_history));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        realm = Realm.getDefaultInstance();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        adapter = new HistoryAdapter();
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter.setItemClickListener(this);
        viewsReady();
        refreshLayout.setOnRefreshListener(this::loadCacheData);
        return view;
    }

    @Override
    public void loadCacheData() {
        super.loadCacheData();
        if (!realm.isClosed()) {
            setRefreshing(true);
            RealmResults<HistoryItemBd> results = realm.where(HistoryItemBd.class).findAllSorted("unixTime", Sort.DESCENDING);
            adapter.addAll(results);
        }
        setRefreshing(false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        realm.close();
    }

    @Override
    public void onItemClick(HistoryItemBd item) {
        Bundle args = new Bundle();
        args.putString(TabFragment.ARG_TITLE, item.getTitle());
        IntentHandler.handle(item.getUrl(), args);
    }

    @Override
    public boolean onItemLongClick(HistoryItemBd item) {
        if (dialogMenu == null) {
            dialogMenu = new AlertDialogMenu<>();
            showedDialogMenu = new AlertDialogMenu<>();
            dialogMenu.addItem(getString(R.string.copy_link), (context, data) -> {
                Utils.copyToClipBoard(data.getUrl());
            });
            dialogMenu.addItem(getString(R.string.delete), (context, data) -> {
                context.delete(data.getId());
            });
        }
        showedDialogMenu.clear();
        showedDialogMenu.addItem(dialogMenu.get(0));
        showedDialogMenu.addItem(dialogMenu.get(1));

        new AlertDialog.Builder(getContext())
                .setItems(showedDialogMenu.getTitles(), (dialog, which) -> {
                    showedDialogMenu.onClick(which, HistoryFragment.this, item);
                })
                .show();
        return true;
    }

    private void delete(int id) {
        if (realm.isClosed())
            return;
        realm.executeTransactionAsync(realm1 -> {
            realm1.where(HistoryItemBd.class)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm();
        }, this::loadCacheData);

    }

    public static void addToHistory(int id, String url, String title) {
        Realm realm = Realm.getDefaultInstance();
        realm.executeTransactionAsync(realm1 -> {
            HistoryItemBd item = realm1
                    .where(HistoryItemBd.class)
                    .equalTo("id", id)
                    .findFirst();
            if (item == null) {
                HistoryItemBd newItem = new HistoryItemBd();
                newItem.setTitle(title);
                newItem.setId(id);
                newItem.setUrl(url);
                newItem.setUnixTime(System.currentTimeMillis());
                newItem.setDate(dateFormat.format(new Date(newItem.getUnixTime())));
                realm1.insert(newItem);
            } else {
                item.setUrl(url);
                item.setUnixTime(System.currentTimeMillis());
                item.setDate(dateFormat.format(new Date(item.getUnixTime())));
                realm1.insertOrUpdate(item);
            }
        }, () -> {
            realm.close();
            HistoryFragment historyFragment = (HistoryFragment) TabManager.getInstance().getByClass(HistoryFragment.class);
            if (historyFragment == null) {
                return;
            }
            historyFragment.loadCacheData();
        });

    }


}
