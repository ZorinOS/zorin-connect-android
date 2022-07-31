package org.kde.kdeconnect.Plugins.SystemVolumePlugin;

import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;
import androidx.recyclerview.widget.RecyclerView;

import org.kde.kdeconnect.BackgroundService;
import com.zorinos.zorin_connect.R;
import com.zorinos.zorin_connect.databinding.ListItemSystemvolumeBinding;

class SinkItemHolder extends RecyclerView.ViewHolder
        implements
        SeekBar.OnSeekBarChangeListener,
        ImageButton.OnClickListener,
        CompoundButton.OnCheckedChangeListener,
        View.OnLongClickListener {

    private final ListItemSystemvolumeBinding viewBinding;
    private final SystemVolumePlugin plugin;
    private final Consumer<Boolean> seekBarTracking;

    private Sink sink;

    public SinkItemHolder(
            @NonNull ListItemSystemvolumeBinding viewBinding,
            @NonNull SystemVolumePlugin plugin,
            @NonNull Consumer<Boolean> seekBarTracking
    ) {
        super(viewBinding.getRoot());
        this.viewBinding = viewBinding;
        this.plugin = plugin;
        this.seekBarTracking = seekBarTracking;

        viewBinding.sinkCard.setOnLongClickListener(this);
        // Replaced RadioButton with TextView because the radio buttons didn't work
        //viewBinding.systemvolumeLabel.setOnLongClickListener(this);

        //viewBinding.systemvolumeLabel.setOnCheckedChangeListener(this);
        viewBinding.systemvolumeMute.setOnClickListener(this);
        viewBinding.systemvolumeSeek.setOnSeekBarChangeListener(this);
    }

    @Override
    public void onProgressChanged(final SeekBar seekBar, int i, boolean triggeredByUser) {
        if (triggeredByUser) {
            BackgroundService.RunCommand(seekBar.getContext(),
                    service -> plugin.sendVolume(sink.getName(), seekBar.getProgress()));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        seekBarTracking.accept(true);
    }

    @Override
    public void onStopTrackingTouch(final SeekBar seekBar) {
        seekBarTracking.accept(false);
        BackgroundService.RunCommand(seekBar.getContext(),
                service -> plugin.sendVolume(sink.getName(), seekBar.getProgress()));
    }

    @Override
    public void onClick(View view) {
        plugin.sendMute(sink.getName(), !sink.isMute());
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
            plugin.sendEnable(sink.getName());
        }
    }

    @Override
    public boolean onLongClick(View v) {
        Toast.makeText(v.getContext(), sink.getName(), Toast.LENGTH_SHORT).show();
        return true;
    }

    public void bind(Sink sink) {
        this.sink = sink;

        //final RadioButton radioButton = viewBinding.systemvolumeLabel;
        //radioButton.setChecked(sink.isDefault());
        //radioButton.setText(sink.getDescription());
        viewBinding.systemvolumeLabel.setSelected(true);
        viewBinding.systemvolumeLabel.setText(sink.getDescription());

        final SeekBar seekBar = viewBinding.systemvolumeSeek;
        seekBar.setMax(sink.getMaxVolume());
        seekBar.setProgress(sink.getVolume());

        int iconRes = sink.isMute() ? R.drawable.ic_volume_mute_black : R.drawable.ic_volume_black;

        ImageButton button = viewBinding.systemvolumeMute;
        button.setImageResource(iconRes);
    }
}
