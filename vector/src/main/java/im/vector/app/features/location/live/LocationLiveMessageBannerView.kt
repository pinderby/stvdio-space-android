/*
 * Copyright (c) 2022 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.location.live

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import com.bumptech.glide.load.resource.bitmap.GranularRoundedCorners
import im.vector.app.R
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.utils.TextUtils
import im.vector.app.databinding.ViewLocationLiveMessageBannerBinding
import im.vector.app.features.themes.ThemeUtils
import org.threeten.bp.Duration

private const val REMAINING_TIME_COUNTER_INTERVAL_IN_MS = 1000L

class LocationLiveMessageBannerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding = ViewLocationLiveMessageBannerBinding.inflate(
            LayoutInflater.from(context),
            this
    )

    val stopButton: Button
        get() = binding.locationLiveMessageBannerStop

    private val background: ImageView
        get() = binding.locationLiveMessageBannerBackground

    private val title: TextView
        get() = binding.locationLiveMessageBannerTitle

    private val subTitle: TextView
        get() = binding.locationLiveMessageBannerSubTitle

    private var countDownTimer: CountDownTimer? = null

    fun render(viewState: LocationLiveMessageBannerViewState) {
        when (viewState) {
            is LocationLiveMessageBannerViewState.Emitter -> renderEmitter(viewState)
            is LocationLiveMessageBannerViewState.Watcher -> renderWatcher(viewState)
        }

        GlideApp.with(context)
                .load(ColorDrawable(ThemeUtils.getColor(context, android.R.attr.colorBackground)))
                .transform(GranularRoundedCorners(0f, 0f, viewState.bottomEndCornerRadiusInDp, viewState.bottomStartCornerRadiusInDp))
                .into(background)
    }

    private fun renderEmitter(viewState: LocationLiveMessageBannerViewState.Emitter) {
        stopButton.isVisible = true
        title.text = context.getString(R.string.location_share_live_enabled)

        countDownTimer?.cancel()
        viewState.remainingTimeInMillis
                .takeIf { it >= 0 }
                ?.let {
                    countDownTimer = object : CountDownTimer(it, REMAINING_TIME_COUNTER_INTERVAL_IN_MS) {
                        override fun onTick(millisUntilFinished: Long) {
                            val duration = Duration.ofMillis(millisUntilFinished.coerceAtLeast(0L))
                            subTitle.text = context.getString(
                                    R.string.location_share_live_remaining_time,
                                    TextUtils.formatDurationWithUnits(context, duration)
                            )
                        }

                        override fun onFinish() {
                            subTitle.text = context.getString(
                                    R.string.location_share_live_remaining_time,
                                    TextUtils.formatDurationWithUnits(context, Duration.ofMillis(0L))
                            )
                        }
                    }
                    countDownTimer?.start()
                }

        val rootLayout: ConstraintLayout? = (binding.root as? ConstraintLayout)
        rootLayout?.let { parentLayout ->
            val constraintSet = ConstraintSet()
            constraintSet.clone(rootLayout)

            if (viewState.isStopButtonCenteredVertically) {
                constraintSet.connect(
                        R.id.locationLiveMessageBannerStop,
                        ConstraintSet.BOTTOM,
                        R.id.locationLiveMessageBannerBackground,
                        ConstraintSet.BOTTOM,
                        0
                )
            } else {
                constraintSet.clear(R.id.locationLiveMessageBannerStop, ConstraintSet.BOTTOM)
            }

            constraintSet.applyTo(parentLayout)
        }
    }

    private fun renderWatcher(viewState: LocationLiveMessageBannerViewState.Watcher) {
        stopButton.isVisible = false
        title.text = context.getString(R.string.location_share_live_view)
        subTitle.text = context.getString(R.string.location_share_live_until, viewState.formattedLocalTimeOfEndOfLive)
    }
}
