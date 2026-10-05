package com.calendarviewbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendarviewbox.BoardState
import com.calendarviewbox.BoardViewModel
import com.calendarviewbox.DisplayTask
import com.calendarviewbox.StoreSearchState
import com.calendarviewbox.data.Prices
import com.calendarviewbox.data.STORE_ALDI
import com.calendarviewbox.data.STORE_COLES
import com.calendarviewbox.data.STORE_OTHER
import com.calendarviewbox.data.STORE_WOOLWORTHS
import com.calendarviewbox.data.StoreProduct
import com.calendarviewbox.data.TodoTask

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Price on the right of a grocery row. Tapping it opens the price editor. */
@Composable
fun PriceTag(task: TodoTask, state: BoardState, onEdit: (TodoTask) -> Unit) {
    val price = state.priceFor(task)
    val qty = Prices.quantity(task.content).count
    Column(
        Modifier
            .clickable(interactionSource = null, indication = null) { onEdit(task) }
            .padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (price == null) {
            Text("Add price", fontSize = 15.sp, color = Ink.Grey)
        } else {
            Text(Prices.money(price.price * qty), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink.Black)
            val age = (System.currentTimeMillis() - price.updatedAt) / DAY_MS
            val note = buildList {
                add(price.shortStore)
                if (qty > 1) add("$qty × ${Prices.money(price.price)}")
                if (price.special) add("special")
                if (age >= 14) add("${age / 7} wk old")
            }.joinToString(", ")
            Text(note, fontSize = 13.sp, color = Ink.DarkGrey, maxLines = 1)
        }
    }
}

/** "About $83.40 for 12 items, 3 without a price." */
@Composable
fun GroceryTotal(tasks: List<DisplayTask>, state: BoardState) {
    if (tasks.isEmpty()) return
    var total = 0.0
    var priced = 0
    tasks.forEach { row ->
        state.priceFor(row.task)?.let {
            total += it.price * Prices.quantity(row.task.content).count
            priced++
        }
    }
    val missing = tasks.size - priced
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (priced == 0) "No prices yet" else "About ${Prices.money(total)}",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Ink.Black,
        )
        if (missing > 0 && priced > 0) {
            Spacer(Modifier.width(10.dp))
            Text("$missing without a price", fontSize = 16.sp, color = Ink.DarkGrey)
        }
    }
}

/** Woolworths and Coles results side by side, so prices are easy to compare. Tap one to pick it. */
@Composable
fun StoreResultsList(search: StoreSearchState, onPick: (StoreProduct) -> Unit) {
    if (search.loading) {
        Text(
            "Searching Woolworths and Coles for \"${search.query}\"... this can take a few seconds.",
            fontSize = 20.sp,
            color = Ink.DarkGrey,
            modifier = Modifier.padding(top = 16.dp),
        )
        return
    }
    if (search.results.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        search.results.forEach { store ->
            Column(Modifier.weight(1f)) {
                Text(store.store, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink.Black)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp).height(2.dp).background(Ink.Black))
                store.problem?.let { Text(it, fontSize = 18.sp, color = Ink.DarkGrey, modifier = Modifier.padding(vertical = 8.dp)) }
                if (store.problem == null && store.products.isEmpty()) {
                    Text("No matches.", fontSize = 18.sp, color = Ink.DarkGrey, modifier = Modifier.padding(vertical = 8.dp))
                }
                store.products.forEach { p -> ProductRow(p, onPick) }
            }
        }
    }
}

@Composable
private fun ProductRow(p: StoreProduct, onPick: (StoreProduct) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null) { onPick(p) }
            .drawBehind { drawLine(Ink.LightGrey, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 20.sp, color = Ink.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(p.size, p.unitPrice).joinToString(", ")
            if (detail.isNotEmpty()) Text(detail, fontSize = 15.sp, color = Ink.DarkGrey, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Prices.money(p.price), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.Black)
            if (p.wasPrice != null) {
                Text("was ${Prices.money(p.wasPrice)}", fontSize = 14.sp, color = Ink.DarkGrey)
            } else if (p.special) {
                Text("special", fontSize = 14.sp, color = Ink.DarkGrey)
            }
        }
    }
}

/**
 * Set the price for one grocery item: search the stores and pick a product, or type a price.
 * The price is also remembered for that item's name next time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceEditorOverlay(task: TodoTask, state: BoardState, vm: BoardViewModel, onClose: () -> Unit) {
    val current = state.priceFor(task)
    val name = Prices.quantity(task.content).name
    var amount by remember(task.id) { mutableStateOf(current?.takeIf { it.store == STORE_OTHER || it.productId == null }?.price?.let { "%.2f".format(it) } ?: "") }
    var store by remember(task.id) { mutableStateOf(current?.store ?: STORE_OTHER) }
    var message by remember(task.id) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 36.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 40.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Ink.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            InkButton("Done", selected = true, onClick = onClose)
        }
        Text(
            current?.let { "Now ${Prices.money(it.price)} at ${it.store}" + (it.size?.let { s -> ", $s" } ?: "") }
                ?: "No price yet.",
            fontSize = 20.sp,
            color = Ink.DarkGrey,
            modifier = Modifier.padding(top = 6.dp),
        )

        Text("Find it at Woolworths or Coles", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink.Black,
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("Search for \"$name\"", selected = true) { vm.searchStores(name) }
        }
        StoreResultsList(state.storeSearch) { product ->
            vm.setItemPrice(task, product)
            message = "Using ${product.name} at ${product.store}, ${Prices.money(product.price)}."
        }

        Text("Or type a price", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink.Black,
            modifier = Modifier.padding(top = 32.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                singleLine = true,
                placeholder = { Text("4.95") },
                prefix = { Text("$") },
                textStyle = TextStyle(fontSize = 22.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(220.dp),
            )
            Spacer(Modifier.width(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(STORE_ALDI, STORE_WOOLWORTHS, STORE_COLES, STORE_OTHER).forEach { s ->
                    InkChip(s, store == s) { store = s }
                }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("Save price", selected = true) {
                val value = Prices.parseMoney(amount)
                if (value == null) {
                    message = "Type a price like 4.95."
                } else {
                    vm.setItemPrice(task, StoreProduct(store, null, name, null, value))
                    message = "Saved ${Prices.money(value)} for $name."
                }
            }
            if (current != null) {
                InkButton("Remove price") {
                    vm.clearItemPrice(task)
                    message = "Price removed."
                }
            }
        }
        message?.let { Text(it, fontSize = 18.sp, color = Ink.Black, modifier = Modifier.padding(top = 12.dp)) }
        Text(
            "Prices come from the stores' websites and may differ in store. Aldi has few prices online, so type those in.",
            fontSize = 15.sp,
            color = Ink.DarkGrey,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/** Small outlined label, e.g. a store name. */
@Composable
fun Tag(text: String) {
    Box(
        Modifier
            .border(1.5.dp, Ink.Black, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp)
    ) { Text(text, fontSize = 13.sp, color = Ink.Black) }
}
