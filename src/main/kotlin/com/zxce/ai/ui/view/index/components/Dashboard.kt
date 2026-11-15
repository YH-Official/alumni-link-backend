package com.zxce.ai.ui.view.index.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.view.index.components.cards.*


@Preview
@Composable
fun Dashboard(modifier: Modifier = Modifier) {
    LazyVerticalStaggeredGrid(
        modifier = modifier,
        columns = StaggeredGridCells.Adaptive(300.dp),
        contentPadding = PaddingValues(5.dp)
    ) {
        item {
            CpuCard()
        }
        item {
            ThreadCard()
        }
        item {
            JvmMemoryCard()
        }
        item {
            MemoryCard()
        }
        item {
            NetWorkCard()
        }
        item {
            OnlineUserCard()
        }
        item(span = StaggeredGridItemSpan.FullLine) {
            NetWorkAddressCard()
        }
    }
}

