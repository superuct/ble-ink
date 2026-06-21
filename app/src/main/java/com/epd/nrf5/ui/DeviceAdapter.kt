package com.epd.nrf5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.epd.nrf5.R

data class DeviceItem(val name: String, val address: String, val rssi: Int)

class DeviceAdapter(
    private val items: List<DeviceItem>,
    private val onConnect: (DeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val btnConnect: View = view.findViewById(R.id.btnConnectDevice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvAddress.text = "${item.address} (${item.rssi}dBm)"
        holder.btnConnect.setOnClickListener { onConnect(item) }
    }

    override fun getItemCount() = items.size
}
