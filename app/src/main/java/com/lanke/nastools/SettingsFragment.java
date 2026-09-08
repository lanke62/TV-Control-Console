package com.lanke.nastools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 设置页：SSH 账号 + 测试/诊断/探测 + NAS 日志（默认 SSH 直连，不再提供 HTTP 模式切换） */
public class SettingsFragment extends BaseFragment {

    private LinearLayout box;
    private boolean loaded = false;
    private TextInputEditText etSshHost, etSshPort, etSshUser, etSshPass;
    private TextView tvResult;
    private Spinner spLogs;
    private TextView tvLogs;
    private final List<String> logKeys = new ArrayList<>();
    private final List<String> logContents = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p,
                             @Nullable Bundle b) {
        Context c = requireContext();
        View root = inf.inflate(R.layout.activity_settings, p, false);
        box = (LinearLayout) root;
        wire(root);
        return makeScroller(c, box);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    @Override
    public void load() {
        Context c = ctx();
        if (c == null || getView() == null) return;
        etSshHost.setText(Prefs.sshHost(c));
        etSshPort.setText(String.valueOf(Prefs.sshPort(c)));
        etSshUser.setText(Prefs.sshUser(c));
        showLastResult();
        loadLogs();
    }

    private void wire(View v) {
        etSshHost = v.findViewById(R.id.etSshHost);
        etSshPort = v.findViewById(R.id.etSshPort);
        etSshUser = v.findViewById(R.id.etSshUser);
        etSshPass = v.findViewById(R.id.etSshPass);
        tvResult = v.findViewById(R.id.tvResult);

        v.findViewById(R.id.btnSshProbe).setOnClickListener(iv -> runDiagnose());

        v.findViewById(R.id.btnSshTest).setOnClickListener(iv -> {
            readSsh();
            tvResult.setText("正在测试 SSH 连接与 sudo 提权…");
            new Thread(() -> {
                Api.R r = Ssh.test(requireContext());
                requireActivity().runOnUiThread(() -> {
                    if (r.ok) {
                        tvResult.setText("连接正常，sudo 已拿到 root 权限。\n"
                                + "接着点「保存并探测目录」，让它找到两个项目的位置。");
                    } else {
                        tvResult.setText("连接失败：\n" + r.msg());
                    }
                });
            }).start();
        });

        v.findViewById(R.id.btnSshSave).setOnClickListener(iv -> {
            readSsh();
            tvResult.setText("已保存。正在探测 NAS 上的项目目录…");
            new Thread(() -> {
                Api.R r = Ssh.probe(requireContext(), true);
                requireActivity().runOnUiThread(() -> {
                    if (r.ok && r.json != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("SSH 可用，sudo 提权成功（root）。\n");
                        sb.append("脚本版本：").append(r.json.optString("version", "")).append("\n");
                        sb.append("python3：").append(r.json.optString("python", "")).append("\n");
                        String cal = r.json.optString("cal_home", "");
                        String proj = r.json.optString("proj_home", "");
                        sb.append("校历目录：").append(cal.isEmpty() ? "没找到" : cal).append("\n");
                        sb.append("投影目录：").append(proj.isEmpty() ? "没找到" : proj).append("\n");
                        if (cal.isEmpty() || proj.isEmpty()) {
                            sb.append("\n有目录没找到。常见原因是部署位置不在 /volume1，"
                                    + "或者 conf 文件不在 <项目>/conf/ 下。");
                        }
                        tvResult.setText(sb.toString());
                    } else {
                        tvResult.setText("探测失败：\n" + r.msg());
                    }
                });
            }).start();
        });

        spLogs = v.findViewById(R.id.spLogs);
        tvLogs = v.findViewById(R.id.tvLogs);
        // 日志区域在页面外层 ScrollView 里，触摸日志区时禁止外层抢滚动，否则滚不动
        tvLogs.setOnTouchListener((v2, ev) -> {
            v2.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        v.findViewById(R.id.btnLogsRead).setOnClickListener(iv -> loadLogs());
        v.findViewById(R.id.btnLogsCopy).setOnClickListener(iv -> copy());
    }

    // ---------------- 诊断（页内弹窗，不再跳转到单独页面）----------------

    private void runDiagnose() {
        readSsh();
        tvResult.setText("正在运行 SSH 连接诊断…");
        new Thread(() -> {
            String text = Ssh.diagnose(requireContext());
            requireActivity().runOnUiThread(() -> showDiagnose(text));
        }).start();
    }

    private void showDiagnose(String text) {
        Context c = requireContext();
        ScrollView sv = new ScrollView(c);
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(40, 30, 40, 30);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(c)
                .setTitle("SSH 连接诊断")
                .setView(sv)
                .setPositiveButton("复制", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) c
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("ssh_diag", text));
                        toast("已复制");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------- 读写 ----------------

    private void readSsh() {
        int port;
        try {
            port = Integer.parseInt(String.valueOf(etSshPort.getText()).trim());
        } catch (Exception e) {
            port = 22;
        }
        String host = String.valueOf(etSshHost.getText()).trim();
        String user = String.valueOf(etSshUser.getText()).trim();
        String pwd = String.valueOf(etSshPass.getText());

        Prefs.saveSsh(requireContext(), host, port, user);
        if (!TextUtils.isEmpty(pwd)) SecurePrefs.putPassword(requireContext(), pwd);
        Ssh.reset();
    }

    private void showLastResult() {
        String cal = Prefs.calHome(requireContext());
        String proj = Prefs.projHome(requireContext());
        if (!cal.isEmpty() || !proj.isEmpty()) {
            tvResult.setText("上次探测结果：\n校历目录："
                    + (cal.isEmpty() ? "没找到" : cal)
                    + "\n投影目录：" + (proj.isEmpty() ? "没找到" : proj));
        }
    }

    private void loadLogs() {
        runAsync(() -> {
            java.util.Map<String, String> q = new java.util.HashMap<>();
            q.put("n", "120");
            return Conn.get(ctx(), "/api/logs", q, Api.T_NORMAL);
        }, o -> {
            Context c = ctx();
            if (c == null || spLogs == null) return;
            if (!(o instanceof Api.R) || !((Api.R) o).ok) {
                tvLogs.setText("读取失败：" + ((o instanceof Api.R) ? ((Api.R) o).msg() : "未知错误"));
                return;
            }
            JSONObject logs = ((Api.R) o).json.optJSONObject("logs");
            logKeys.clear();
            logContents.clear();
            if (logs != null) {
                JSONArray names = logs.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String n = names.optString(i);
                        // 兼容旧版 NAS 脚本：不显示 Media 相关日志
                        if (n == null || n.contains("Media") || n.contains("media")) continue;
                        if ("投影仪".equals(n)) n = "电视端";
                        JSONArray arr = logs.optJSONArray(n);
                        StringBuilder sb = new StringBuilder();
                        if (arr != null) {
                            for (int j = arr.length() - 1; j >= 0; j--) {
                                sb.append(arr.optString(j)).append("\n");
                            }
                        }
                        if (sb.length() == 0) sb.append("（暂无内容）");
                        logKeys.add(n);
                        logContents.add(sb.toString());
                    }
                }
            }
            ArrayAdapter<String> ad = new ArrayAdapter<>(c,
                    android.R.layout.simple_spinner_dropdown_item, logKeys);
            spLogs.setAdapter(ad);
            final int[] last = {0};
            spLogs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    last[0] = position;
                    if (position < logContents.size()) tvLogs.setText(logContents.get(position));
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            if (!logContents.isEmpty()) tvLogs.setText(logContents.get(0));
        });
    }

    private void copy() {
        Context c = ctx();
        if (c == null || tvLogs == null) return;
        String s = tvLogs.getText().toString();
        if (TextUtils.isEmpty(s)) return;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("nas_log", s));
            toast("已复制");
        }
    }
}
