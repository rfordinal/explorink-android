package org.explorink.gpsbridge

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * Spinner adapter for the pin-kind save picker, per Outline "Dizajn systém"
 * "Riadok zoznamu": each kind shows its own glyph (PinIcons.kt) next to its
 * label, the same look collapsed and in the dropdown list -- a save picker
 * is a short list of kinds, not a disclosure menu, so one row shape serves
 * both roles.
 *
 * [labels] is passed in separately from [kinds] rather than read off
 * `kind.label` here, because [PinsActivity.renderSpinner] appends "(saved)"
 * to some of them -- that marking is this screen's concern, not the glyph's.
 */
class PinKindAdapter(
    context: Context,
    private val kinds: List<PinKinds.Kind>,
    private val labels: List<String>,
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = kinds.size
    override fun getItem(position: Int) = kinds[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?) =
        bind(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?) =
        bind(position, convertView, parent)

    private fun bind(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.pin_kind_row, parent, false)
        val kind = kinds[position]
        val iconRes = PinIcons.iconFor(kind.key)
        val iv = view.findViewById<ImageView>(R.id.ivKindIcon)
        val tvGlyph = view.findViewById<TextView>(R.id.tvKindGlyph)
        if (iconRes != null) {
            iv.setImageResource(iconRes)
            iv.visibility = View.VISIBLE
            tvGlyph.visibility = View.GONE
        } else {
            iv.visibility = View.GONE
            tvGlyph.visibility = View.VISIBLE
            tvGlyph.text = PinIcons.glyphTextFor(kind.key) ?: "?"
        }
        view.findViewById<TextView>(R.id.tvKindLabel).text = labels[position]
        return view
    }
}
