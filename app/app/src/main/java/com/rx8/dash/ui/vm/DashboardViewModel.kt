package com.rx8.dash.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rx8.dash.AppGraph
import com.rx8.dash.core.Metric
import kotlinx.coroutines.launch

/** Gauge-slot editing for the dashboard. Live values come from [SessionViewModel]. */
class DashboardViewModel(private val graph: AppGraph) : ViewModel() {

    fun setSlot(index: Int, metric: Metric) {
        viewModelScope.launch {
            graph.settings.update { s ->
                val slots = s.gaugeSlots.toMutableList()
                if (index in slots.indices) slots[index] = metric else slots.add(metric)
                s.copy(gaugeSlots = slots)
            }
        }
    }

    fun addSlot(metric: Metric) {
        viewModelScope.launch {
            graph.settings.update { s ->
                if (s.gaugeSlots.size >= MAX_SLOTS) s else s.copy(gaugeSlots = s.gaugeSlots + metric)
            }
        }
    }

    fun removeSlot(index: Int) {
        viewModelScope.launch {
            graph.settings.update { s ->
                if (index !in s.gaugeSlots.indices || s.gaugeSlots.size <= 1) {
                    s
                } else {
                    s.copy(gaugeSlots = s.gaugeSlots.filterIndexed { i, _ -> i != index })
                }
            }
        }
    }

    companion object {
        const val MAX_SLOTS: Int = 8

        /** Metrics that make sense in a slot (RPM has the tach). */
        val PICKABLE: List<Metric> = Metric.values().filter { it != Metric.RPM && it != Metric.OIL_PRESSURE_OK }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardViewModel(graph()) }
        }
    }
}
