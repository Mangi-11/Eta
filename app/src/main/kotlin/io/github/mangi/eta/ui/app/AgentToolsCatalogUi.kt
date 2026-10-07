package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi

internal fun buildToolsState(context: Context): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = context.getString(R.string.state_screens_and_controls_3f095b),
                tools = listOf(
                    ToolItemUi("observe_screen", context.getString(R.string.tool_ui_watch_the_screen_e70f2a), context.getString(R.string.tool_ui_read_the_current_node_and_attach_the_original_im_df1fec)),
                    ToolItemUi("tap_element", context.getString(R.string.tool_ui_click_element_7a3d91), context.getString(R.string.tool_ui_click_on_the_most_recently_observed_node_b4cf5a)),
                    ToolItemUi("tap_area", context.getString(R.string.tool_ui_click_area_cbaa08), context.getString(R.string.tool_ui_click_by_coordinate_area_2ad961)),
                    ToolItemUi("long_press", context.getString(R.string.tool_ui_long_press_f7a417), context.getString(R.string.tool_ui_long_press_on_coordinates_or_elements_796384)),
                    ToolItemUi("swipe", context.getString(R.string.tool_ui_slide_3723aa), context.getString(R.string.tool_ui_perform_up_down_left_and_right_swipe_gestures_3ef0de)),
                    ToolItemUi("scroll", context.getString(R.string.tool_ui_scroll_220e68), context.getString(R.string.tool_ui_scroll_the_page_or_specify_a_node_83ab24)),
                ),
            ),
            ToolGroupUi(
                id = "text",
                title = context.getString(R.string.state_text_and_clipboard_3a7340),
                tools = listOf(
                    ToolItemUi("type_text", context.getString(R.string.tool_ui_enter_text_ae47ab), context.getString(R.string.tool_ui_type_text_summary)),
                    ToolItemUi("wait_for_text", context.getString(R.string.tool_ui_wait_for_text_9e9a54), context.getString(R.string.tool_ui_wait_for_the_specified_text_to_appear_on_the_scr_43f9b0)),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = context.getString(R.string.state_web_browsing_e56105),
                tools = listOf(
                    ToolItemUi("web_search", context.getString(R.string.tool_web_search), context.getString(R.string.tool_web_search_description)),
                    ToolItemUi("fetch_url", context.getString(R.string.tool_fetch_url), context.getString(R.string.tool_fetch_url_description)),
                    ToolItemUi("browser_use", context.getString(R.string.tool_ui_agent_browser_a66bd5), context.getString(R.string.tool_ui_open_web_pages_off_screen_and_keep_a_takeover_br_72972e)),
                    ToolItemUi("browser_read", context.getString(R.string.tool_ui_read_web_pages_4f0bb9), context.getString(R.string.tool_ui_extract_rendered_text_lists_and_links_8bdcdd)),
                    ToolItemUi("browser_interact", context.getString(R.string.tool_ui_web_page_interaction_331b3f), context.getString(R.string.tool_ui_find_click_and_enter_page_elements_8f102d)),
                    ToolItemUi("browser_screenshot", context.getString(R.string.tool_ui_page_screenshot_c823a2), context.getString(R.string.tool_ui_give_the_current_web_page_viewport_to_the_visual_f62274)),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = context.getString(R.string.state_applications_and_systems_9624e6),
                tools = listOf(
                    ToolItemUi("search_apps", context.getString(R.string.tool_ui_search_apps_897fdf), context.getString(R.string.tool_ui_query_installed_applications_by_name_or_package__32b004)),
                    ToolItemUi("get_current_context", context.getString(R.string.tool_ui_time_and_location_693893), context.getString(R.string.tool_ui_read_system_time_and_recent_location_b9f4ae)),
                    ToolItemUi("launch_app", context.getString(R.string.tool_ui_open_app_7c65e7), context.getString(R.string.tool_ui_start_the_specified_package_name_or_application__beabff)),
                    ToolItemUi("open_uri", context.getString(R.string.tool_ui_open_with_app_32c24e), context.getString(R.string.tool_ui_explicitly_hand_over_links_or_deep_links_to_exte_35ff26)),
                    ToolItemUi("press_key", context.getString(R.string.tool_ui_button_02eafa), context.getString(R.string.tool_ui_system_buttons_such_as_return_homepage_recent_ta_1b4cf0)),
                    ToolItemUi("open_system_panel", context.getString(R.string.tool_ui_system_panel_b0f7a3), context.getString(R.string.tool_ui_open_the_notification_bar_quick_settings_and_oth_5e51cf)),
                ),
            ),
            ToolGroupUi(
                id = "device_direct",
                title = context.getString(R.string.state_direct_access_to_equipment_eda92c),
                tools = io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.filterNot { it.personal }.map {
                    ToolItemUi(it.name, it.title, it.description)
                } + listOf(
                    ToolItemUi("list_alarms", context.getString(R.string.tool_ui_alarm_clock_schedule_acae32), context.getString(R.string.tool_ui_read_the_alarm_clock_that_has_been_created_in_th_2320d6)),
                    ToolItemUi("list_active_timers", context.getString(R.string.tool_ui_activity_timer_36f107), context.getString(R.string.tool_ui_read_running_or_paused_timers_3437c8)),
                    ToolItemUi("get_setting", context.getString(R.string.tool_ui_read_system_settings_d455ce), context.getString(R.string.tool_ui_read_the_specified_settings_key_496975)),
                    ToolItemUi("set_setting", context.getString(R.string.tool_ui_modify_system_settings_ae1f4c), context.getString(R.string.tool_ui_modify_android_settings_keys_91a37e)),
                    ToolItemUi("set_device_state", context.getString(R.string.tool_ui_network_switch_834347), context.getString(R.string.tool_ui_directly_control_wi_fi_or_bluetooth_4fa0b9)),
                    ToolItemUi("app_state_control", context.getString(R.string.tool_ui_application_status_930ff0), context.getString(R.string.tool_ui_stop_freeze_or_unfreeze_apps_a27438)),
                    ToolItemUi("get_logcat", context.getString(R.string.tool_ui_system_log_096733), context.getString(R.string.tool_ui_bounded_reading_and_filtering_of_recent_logs_0a268a)),
                    ToolItemUi("set_alarm", context.getString(R.string.tool_ui_set_alarm_25ca3c), context.getString(R.string.tool_ui_create_a_system_alarm_directly_and_open_the_cloc_9aa214)),
                    ToolItemUi("set_timer", context.getString(R.string.tool_ui_set_timer_aee60c), context.getString(R.string.tool_ui_directly_create_system_timers_up_to_24_hours_87c476)),
                    ToolItemUi("inspect_app", context.getString(R.string.tool_inspect_app), context.getString(R.string.tool_inspect_app_description)),
                    ToolItemUi("device_status", context.getString(R.string.tool_ui_device_status_567a4c), context.getString(R.string.tool_ui_read_power_memory_storage_and_system_version_c501d5)),
                    ToolItemUi("network_info", context.getString(R.string.tool_ui_network_status_6bd556), context.getString(R.string.tool_ui_read_networking_method_and_current_wi_fi_status_68016a)),
                    ToolItemUi("media_control", context.getString(R.string.tool_ui_media_control_585edc), context.getString(R.string.tool_ui_play_pause_and_switch_songs_without_operating_th_311cb8)),
                    ToolItemUi("set_volume", context.getString(R.string.tool_ui_set_volume_85a691), context.getString(R.string.tool_ui_set_by_media_alarm_clock_ringtone_and_other_chan_3fcc3e)),
                    ToolItemUi("top_memory_apps", context.getString(R.string.tool_ui_memory_ranking_408ca1), context.getString(R.string.tool_ui_view_the_currently_most_occupied_processes_8646c4)),
                    ToolItemUi("top_storage_apps", context.getString(R.string.tool_ui_storage_ranking_86a16c), context.getString(R.string.tool_ui_check_application_data_and_cache_usage_837e9f)),
                ),
            ),
            ToolGroupUi(
                id = "personal_data",
                title = context.getString(R.string.state_direct_access_to_personal_data_387d7b),
                tools = io.github.mangi.eta.agent.context.PersonalSearchTools.searches.map {
                    ToolItemUi(it.name, it.title, it.description)
                } + io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.filter { it.personal }.map {
                    ToolItemUi(it.name, it.title, it.description)
                } + listOf(
                    ToolItemUi("read_personal_item", "读取检索详情", "读取历史检索结果的完整条目。"),
                    ToolItemUi("summarize_bills", "账单汇总", "按时间与关键词精确汇总账单索引。"),
                    ToolItemUi("read_sms_code", context.getString(R.string.tool_ui_read_verification_code_7d1121), context.getString(R.string.tool_ui_only_extract_verification_codes_from_recent_sms__0fb8c1)),
                    ToolItemUi("recent_notifications", context.getString(R.string.tool_ui_read_notification_7fdc09), context.getString(R.string.tool_ui_read_the_current_notification_title_and_text_0faee7)),
                    ToolItemUi("search_notification_history", context.getString(R.string.tool_ui_notification_history_95d015), context.getString(R.string.tool_ui_retrieve_the_last_7_days_of_notifications_saved__643e43)),
                    ToolItemUi("recent_app_activity", context.getString(R.string.tool_ui_recently_applied_08f74c), context.getString(R.string.tool_ui_view_recently_opened_apps_and_times_bf9d50)),
                    ToolItemUi("app_usage_summary", context.getString(R.string.tool_ui_app_usage_statistics_ee20d3), context.getString(R.string.tool_ui_summarize_recent_app_usage_by_foreground_duratio_b346c8)),
                    ToolItemUi("get_current_location", context.getString(R.string.tool_ui_current_location_b458ea), context.getString(R.string.tool_ui_read_the_closest_location_the_system_already_has_255a6c)),
                    ToolItemUi("get_device_environment", context.getString(R.string.tool_ui_equipment_environment_1026ec), context.getString(R.string.tool_ui_read_lock_screen_do_not_disturb_audio_output_and_9260b8)),
                    ToolItemUi("search_clipboard_history", context.getString(R.string.tool_ui_clipboard_history_b377bb), context.getString(R.string.tool_ui_retrieve_clipboard_contents_saved_by_system_inpu_1dc9db)),
                    ToolItemUi("get_health_summary", context.getString(R.string.tool_ui_health_summary_951c0b), context.getString(R.string.tool_ui_summarize_steps_sleep_exercise_and_body_metrics_6ff66f)),
                    ToolItemUi("wifi_credentials", context.getString(R.string.tool_ui_wi_fi_password_80e9a4), context.getString(R.string.tool_ui_read_the_network_credentials_saved_by_the_phone_96d43a)),
                    ToolItemUi("search_media", context.getString(R.string.tool_ui_album_pictures_23bcc2), context.getString(R.string.tool_ui_retrieve_pictures_by_file_name_or_album_path_c08236)),
                    ToolItemUi("search_audio", context.getString(R.string.tool_ui_audio_file_1ccf2e), context.getString(R.string.tool_ui_search_audio_by_title_filename_or_author_82e20d)),
                    ToolItemUi("search_recordings", context.getString(R.string.tool_ui_system_recording_15eb19), context.getString(R.string.tool_ui_retrieve_recording_files_from_system_media_libra_314c4d)),
                    ToolItemUi("search_files", context.getString(R.string.tool_ui_share_files_a3b376), context.getString(R.string.tool_ui_retrieve_documents_and_files_from_shared_storage_7d6193)),
                    ToolItemUi("search_calendar_events", context.getString(R.string.tool_ui_calendar_events_970349), context.getString(R.string.tool_ui_search_events_by_title_location_or_description_1afd77)),
                    ToolItemUi("search_contacts", context.getString(R.string.tool_ui_address_book_9070cb), context.getString(R.string.tool_ui_retrieve_contact_name_and_open_address_6dacc5)),
                    ToolItemUi("search_call_history", context.getString(R.string.tool_ui_call_history_88e57b), context.getString(R.string.tool_ui_retrieve_calls_by_number_or_contact_name_2ce431)),
                    ToolItemUi("search_messages", context.getString(R.string.tool_ui_short_message_17e1a4), context.getString(R.string.tool_ui_search_text_messages_by_sender_or_text_keywords_e14363)),
                    ToolItemUi("search_downloads", context.getString(R.string.tool_ui_download_history_8494d7), context.getString(R.string.tool_ui_retrieve_system_download_tasks_and_files_3301b9)),
                    ToolItemUi("search_notes", context.getString(R.string.tool_ui_coloros_notes_6c324c), context.getString(R.string.tool_ui_retrieve_notes_to_dos_and_text_content_e806d7)),
                    ToolItemUi("search_coloros_recordings", context.getString(R.string.tool_ui_coloros_recording_a4e425), context.getString(R.string.tool_ui_retrieve_normal_recordings_and_call_recordings_55c192)),
                    ToolItemUi("search_recording_summaries", context.getString(R.string.tool_ui_recording_summary_2fe550), context.getString(R.string.tool_ui_retrieve_transcribed_summaries_and_notes_associa_9cb00f)),
                    ToolItemUi("search_system_memories", context.getString(R.string.tool_ui_coloros_system_memory_eff961), context.getString(R.string.tool_ui_retrieve_collected_information_and_its_structure_9c1c71)),
                    ToolItemUi("search_saved_places", context.getString(R.string.tool_ui_save_location_c29782), context.getString(R.string.tool_ui_retrieve_location_information_from_system_memory_52ea48)),
                    ToolItemUi("search_personal_orders", context.getString(R.string.tool_ui_personal_order_25e4c9), context.getString(R.string.tool_ui_retrieve_takeout_shopping_express_delivery_ticke_f8d002)),
                    ToolItemUi("search_qq_chat_images", context.getString(R.string.tool_ui_qq_chat_pictures_e21bf9), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_qq_chat_picture_cach_b8f009)),
                    ToolItemUi("search_wechat_chat_images", context.getString(R.string.tool_ui_wechat_chat_pictures_72b268), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_wechat_chat_picture__ab66f7)),
                ),
            ),
            ToolGroupUi(
                id = "file_vision",
                title = context.getString(R.string.state_document_vision_6a65a7),
                tools = listOf(
                    ToolItemUi("read_image", context.getString(R.string.tool_ui_read_pictures_ae993b), context.getString(R.string.tool_ui_read_pictures_of_known_paths_and_hand_them_over__7f9569)),
                ),
            ),
            ToolGroupUi(
                id = "memory",
                title = context.getString(R.string.state_memory_b55ff5),
                tools = listOf(
                    ToolItemUi("memory_get", context.getString(R.string.tool_ui_read_memory_979135), context.getString(R.string.tool_ui_paged_to_read_or_retrieve_long_term_memory_in_me_88afc4)),
                    ToolItemUi("memory_write", context.getString(R.string.tool_ui_organize_memory_2b08eb), context.getString(R.string.tool_ui_partially_update_append_or_clear_long_term_memor_c1bab6)),
                    ToolItemUi("character_memory_get", "读取剧情记忆", "仅角色会话可用，读取当前角色的长期剧情和关系。"),
                    ToolItemUi("character_memory_write", "整理剧情记忆", "仅角色会话可用，更新当前角色的剧情记忆，不写入现实 MEMORY.md。"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = context.getString(R.string.state_terminal_and_files_ae7c54),
                tools = listOf(
                    ToolItemUi("terminal", context.getString(R.string.tool_ui_session_terminal_09c6e6), context.getString(R.string.tool_ui_user_root_shell_conversational_execution_and_asy_13c2ab)),
                    ToolItemUi("edit_file", context.getString(R.string.tool_edit_file), context.getString(R.string.tool_edit_file_description)),
                    ToolItemUi("stat_file", context.getString(R.string.tool_stat_file), context.getString(R.string.tool_stat_file_description)),
                    ToolItemUi("glob_files", context.getString(R.string.tool_glob_files), context.getString(R.string.tool_glob_files_description)),
                    ToolItemUi("grep_files", context.getString(R.string.tool_grep_files), context.getString(R.string.tool_grep_files_description)),
                    ToolItemUi("read_file", context.getString(R.string.tool_ui_read_file_dc995c), context.getString(R.string.tool_ui_read_the_contents_of_mobile_phone_files_bf3066)),
                    ToolItemUi("write_file", context.getString(R.string.tool_ui_write_file_e620fd), context.getString(R.string.tool_ui_write_or_overwrite_mobile_files_29fae4)),
                    ToolItemUi("list_directory", context.getString(R.string.tool_ui_list_directory_96e765), context.getString(R.string.tool_ui_list_directory_contents_feff30)),
                ),
            ),
        )
    )
