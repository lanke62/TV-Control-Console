package com.lanke.nastools;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 各标签页的公共基类：下拉刷新 + 后台线程跑网络 + 回到主线程更新 UI */
public abstract class BaseFragment extends Fragment {

    protected SwipeRefreshLayout swipe;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    protected boolean busy = false;

    @Nullable
    protected Context ctx() {
        return getContext();
    }

    /** 子类用它初始化内容区，返回带下拉刷新的容器（SwipeRefreshLayout 包 ScrollView） */
    protected View makeScroller(Context c, ViewGroup content) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        swipe = new SwipeRefreshLayout(c);
        swipe.addView(sv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipe.setOnRefreshListener(this::load);
        // 关键：返回 swipe 本身（此前误返回 sv，导致 SwipeRefreshLayout 没进视图树，下拉刷新失灵）
        return swipe;
    }

    /** 子类实现：拉取数据并刷新界面 */
    public abstract void load();

    protected void setBusy(boolean b) {
        busy = b;
        if (swipe != null && !b) swipe.setRefreshing(false);
    }

    protected void runAsync(Callable<Object> bg, Consumer<Object> ui) {
        // 离屏的 Fragment 可能已被销毁，此时 getContext() 为 null，别发起请求
        if (busy || !isAdded() || getContext() == null) return;
        busy = true;
        if (swipe != null) swipe.setRefreshing(true);
        exec.execute(() -> {
            Object result;
            try {
                result = bg.call();
            } catch (Throwable e) {
                result = e;
            }
            final Object out = result;
            main.post(() -> {
                busy = false;
                if (swipe != null) swipe.setRefreshing(false);
                if (!isAdded()) return;
                try {
                    ui.accept(out);
                } catch (Throwable t) {
                    // 任何界面渲染 / JSON 解析异常都不要变成闪退，改成把异常显示出来
                    Log.e("BaseFragment", "ui 渲染异常（已拦截）", t);
                    Context c = ctx();
                    if (c != null) {
                        showOutput("界面渲染出错（已拦截，未崩溃）",
                                t.getClass().getName() + "：\n" + String.valueOf(t.getMessage()));
                    }
                }
            });
        });
    }

    protected void toast(String s) {
        Context c = ctx();
        if (c != null) Ui.toast(c, s);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    /** 弹出一个可滚动的纯文本对话框，用来显示脚本输出 */
    protected void showOutput(String title, String body) {
        Context c = ctx();
        if (c == null) return;
        TextView tv = new TextView(c);
        tv.setText(TextUtils.isEmpty(body) ? "（无输出）" : body);
        tv.setTextIsSelectable(true);
        tv.setTextSize(12f);
        int p = Ui.dp(c, 16);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(c);
        sv.addView(tv);
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
