package com.shengqiao.app.ui.devices;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.shengqiao.app.PcDiscovery;
import com.shengqiao.app.R;
import com.shengqiao.app.databinding.ItemDiscoveredHostBinding;

final class HostAdapter extends ListAdapter<PcDiscovery.Host, HostAdapter.HostViewHolder> {
    interface Listener {
        void onHostSelected(PcDiscovery.Host host);
    }

    private static final DiffUtil.ItemCallback<PcDiscovery.Host> DIFF =
            new DiffUtil.ItemCallback<PcDiscovery.Host>() {
                @Override
                public boolean areItemsTheSame(@NonNull PcDiscovery.Host oldItem,
                                               @NonNull PcDiscovery.Host newItem) {
                    return oldItem.name.equals(newItem.name);
                }

                @Override
                public boolean areContentsTheSame(@NonNull PcDiscovery.Host oldItem,
                                                  @NonNull PcDiscovery.Host newItem) {
                    return oldItem.name.equals(newItem.name)
                            && oldItem.address.equals(newItem.address)
                            && oldItem.port == newItem.port;
                }
            };

    private final Listener listener;

    HostAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public HostViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDiscoveredHostBinding binding = ItemDiscoveredHostBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new HostViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull HostViewHolder holder, int position) {
        PcDiscovery.Host host = getItem(position);
        holder.binding.hostName.setText(host.name);
        holder.binding.hostAddress.setText(holder.itemView.getContext().getString(
                R.string.device_address_format, host.address, host.port));
        holder.binding.hostCard.setOnClickListener(view -> listener.onHostSelected(host));
        holder.binding.hostConnectButton.setOnClickListener(view -> listener.onHostSelected(host));
    }

    static final class HostViewHolder extends RecyclerView.ViewHolder {
        private final ItemDiscoveredHostBinding binding;

        HostViewHolder(ItemDiscoveredHostBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
