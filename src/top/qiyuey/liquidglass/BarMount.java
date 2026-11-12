package top.qiyuey.liquidglass;

import android.view.ViewGroup;

/** A resolved mount keeps the sample subtree separate from the glass overlay. */
class BarMount {
    final ViewGroup parent;
    final ViewGroup backdrop;
    BarMount(ViewGroup parent, ViewGroup backdrop) {
        this.parent = parent;
        this.backdrop = backdrop;
    }

}
