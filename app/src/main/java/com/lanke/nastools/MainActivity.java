package com.lanke.nastools;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 pager;
    private final Fragment[] frags = new Fragment[4];

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("家庭联网控制");
        tb.setTitleTextColor(0xFFFFFFFF);
        // 标题旁小字显示当前版本号
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tb.setSubtitle("v" + ver);
            tb.setSubtitleTextColor(0xB3FFFFFF);
        } catch (Exception ignored) {
        }
        setSupportActionBar(tb);

        pager = findViewById(R.id.pager);
        pager.setOffscreenPageLimit(3);   // 四页都常驻，切换不重新加载
        pager.setAdapter(new PagerAdapter(this));

        TabLayout tabs = findViewById(R.id.tabs);
        String[] names = {"控制", "设备", "校历", "设置"};
        new TabLayoutMediator(tabs, pager, (tab, pos) -> tab.setText(names[pos])).attach();
    }

    /** 供子页跳转（例如「控制」页没有设备时跳去「设备」页添加） */
    public void gotoTab(int index) {
        if (pager != null) pager.setCurrentItem(index, true);
    }

    private Fragment make(int i) {
        switch (i) {
            case 0: return new ControlFragment();
            case 1: return new DeviceListFragment();
            case 2: return new CalendarFragment();
            default: return new SettingsFragment();
        }
    }

    class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(@NonNull FragmentActivity a) {
            super(a);
        }

        @NonNull
        @Override
        public Fragment createFragment(int pos) {
            if (frags[pos] == null) frags[pos] = make(pos);
            return frags[pos];
        }

        @Override
        public int getItemCount() {
            return 4;
        }
    }
}
