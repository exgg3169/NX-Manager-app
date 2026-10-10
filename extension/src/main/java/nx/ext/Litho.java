package nx.ext;

/**
 * Decides which feed components to hide. YouTube builds most screens from server-described
 * components; each has an identifier and a path (its parents' names), e.g.
 * {@code home_video_with_context.eml|...}. Matching names are replaced by an empty component.
 */
@SuppressWarnings("unused")
public final class Litho {
    private Litho() {
    }

    // Containers of regular content whose children must never be hidden as ads.
    private static final String[] AD_EXCEPTIONS = {
            "|comment.", "comment_thread", "home_video_with_context", "library_recent_shelf",
            "related_video_with_context",
    };

    private static final String[] GENERAL_ADS = {
            "_ad_with", "_buttoned_layout", "ads_video_with_context", "banner_text_icon", "brand_video_shelf",
            "brand_video_singleton", "carousel_ad", "carousel_footered_layout", "carousel_headered_layout",
            "compact_landscape_image_layout", "composite_concurrent_carousel_layout",
            "full_width_portrait_image_layout", "full_width_square_image_carousel_layout",
            "full_width_square_image_layout", "hero_promo_image", "image_button_group_layout",
            "landscape_image_carousel_layout", "landscape_image_wide_button_layout", "primetime_promo",
            "product_details", "square_image_layout", "text_image_button_layout", "text_image_no_button_layout",
            "video_display_button_group_layout", "video_display_carousel_button_group_layout",
            "video_display_carousel_buttoned_short_dr_layout", "video_display_full_buttoned_short_dr_layout",
            "video_display_full_layout", "watch_metadata_app_promo", "cta_shelf_card", "paid_content_overlay",
    };

    private static final String[] PREMIUM = {
            "statement_banner", "offer_module_root", "movie_and_show_upsell_card", "compact_movie",
            "compact_tvfilm_item", "horizontal_movie_shelf", "browsy_bar",
    };

    private static final String[] MERCH = {
            "product_carousel", "shopping_carousel.e", "product_item", "products_in_video",
            "shopping_description_shelf.e", "shopping_description_item.e", "shopping_overlay.e",
            "product_sticker.e",
    };

    private static final String[] SHORTS_SHELVES = {
            "shorts_shelf", "inline_shorts", "shorts_grid", "shorts_video_cell",
    };

    /** Called for every component before it is built. Returns true to hide it. */
    public static boolean isFiltered(String identifier, StringBuilder pathBuilder) {
        try {
            String id = identifier == null ? "" : identifier;
            String path = pathBuilder == null ? "" : pathBuilder.toString();

            if (Features.has(Features.SHORTS) && Nx.bool(NxSettings.HIDE_SHORTS_SHELF) && (containsAny(id, SHORTS_SHELVES) || containsAny(path, SHORTS_SHELVES))) {
                return true;
            }
            if (!Features.has(Features.FEED_ADS) || containsAny(path, AD_EXCEPTIONS)) return false;
            if (Nx.bool(NxSettings.HIDE_FEED_ADS) && (containsAny(path, GENERAL_ADS) || id.startsWith("carousel_ad"))) {
                return true;
            }
            if (Nx.bool(NxSettings.HIDE_PREMIUM) && containsAny(path, PREMIUM)) return true;
            if (Nx.bool(NxSettings.HIDE_MERCH) && containsAny(path, MERCH)) return true;
        } catch (Throwable t) {
            Nx.log("Litho filter failed", t);
        }
        return false;
    }

    private static boolean containsAny(String value, String[] needles) {
        if (value.isEmpty()) return false;
        for (String n : needles) if (value.contains(n)) return true;
        return false;
    }
}
