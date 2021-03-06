(ns quantum.untyped.ui.keys
  (:require
    [clojure.set :as set]))

(def ^{:doc "From https://www.w3.org/TR/uievents-key/#named-key-attribute-values, accessed 2/1/2018"}
  key-ident->label
  {;; Special keys
   "Unidentified"              :unidentified
   ;; Modifier keys
   "Alt"                       :alt
   "AltGraph"                  :alt-graph
   "CapsLock"                  :caps-lock
   "Control"                   :control
   "Fn"                        :fn
   "FnLock"                    :fn-lock
   "Meta"                      :meta
   "NumLock"                   :num-lock
   "ScrollLock"                :scroll-lock
   "Shift"                     :shift
   "Symbol"                    :symbol
   "SymbolLock"                :symbol-lock
   ;; Whitespace keys
   "Enter"                     :enter ; doubles as 'return' key
   "Tab"                       :tab
   ;; Navigation keys
   "ArrowDown"                 :down
   "ArrowLeft"                 :left
   "ArrowRight"                :right
   "ArrowUp"                   :up
   "End"                       :end
   "Home"                      :home
   "PageDown"                  :page-down
   "PageUp"                    :page-up
   ;; Editing keys
   "Backspace"                 :backspace
   "Clear"                     :clear
   "Copy"                      :copy
   "CrSel"                     :cursor-select
   "Cut"                       :cut
   "Delete"                    :delete
   "EraseEof"                  :erase-eof
   "ExSel"                     :extend-selection
   "Insert"                    :insert
   "Paste"                     :paste
   "Redo"                      :redo
   "Undo"                      :undo
   ;; UI keys
   "Accept"                    :accept
   "Again"                     :again
   "Attn"                      :attention
   "Cancel"                    :cancel
   "ContextMenu"               :context-menu
   "Escape"                    :escape
   "Execute"                   :execute
   "Find"                      :find
   "Help"                      :help
   "Pause"                     :pause
   "Play"                      :play
   "Props"                     :props
   "Select"                    :select
   "ZoomIn"                    :zoom-in
   "ZoomOut"                   :zoom-out
   ;; Device keys
   "BrightnessDown"            :brightness-down
   "BrightnessUp"              :brightness-up
   "Eject"                     :eject
   "LogOff"                    :log-off
   "Power"                     :power
   "PowerOff"                  :power-off
   "PrintScreen"               :print-screen
   "Hibernate"                 :hibernate
   "Standby"                   :standby
   "WakeUp"                    :wake-up
   ;; IME and composition keys
   "AllCandidates"             :all-candidates
   "Alphanumeric"              :alpha-numeric
   "CodeInput"                 :code-input
   "Compose"                   :compose
   "Convert"                   :convert
   "Dead"                      :dead
   "FinalMode"                 :final-mode
   "GroupFirst"                :group-first
   "GroupLast"                 :group-last
   "GroupNext"                 :group-next
   "GroupPrevious"             :group-previous
   "ModeChange"                :mode-change
   "NextCandidate"             :next-candidate
   "NonConvert"                :non-convert
   "PreviousCandidate"         :previous-candidate
   "Process"                   :process
   "SingleCandidate"           :single-candidate
   ;; Korean keyboards
   "HangulMode"                :hangul-mode
   "HanjaMode"                 :hanja-mode
   "JunjaMode"                 :junja-mode
   ;; Japanese keyboards
   "Eisu"                      :eisu
   "Hankaku"                   :hankaku
   "Hiragana"                  :hiragana
   "HiraganaKatakana"          :hiragana-katakana
   "KanaMode"                  :kana-mode
   "KanjiMode"                 :kanji-mode
   "Katakana"                  :katakana
   "Romaji"                    :romaji
   "Zenkaku"                   :zenkaku
   "ZenkakuHankaku"            :zenkaku-hankaku
   ;; General-purpose function keys (only the first few are defined below)
   "F1"                        :f1
   "F2"                        :f2
   "F3"                        :f3
   "F4"                        :f4
   "F5"                        :f5
   "F6"                        :f6
   "F7"                        :f7
   "F8"                        :f8
   "F9"                        :f9
   "F10"                       :f10
   "F11"                       :f11
   "F12"                       :f12
   "Soft1"                     :soft-1
   "Soft2"                     :soft-2
   "Soft3"                     :soft-3
   "Soft4"                     :soft-4
   ;; Multimedia keys
   "ChannelDown"               :channel-down
   "ChannelUp"                 :channel-up
   "Close"                     :close
   "MailForward"               :mail-forward
   "MailReply"                 :mail-reply
   "MailSend"                  :mail-send
   "MediaClose"                :media-close
   "MediaFastForward"          :media-fast-forward
   "MediaPause"                :media-pause
   "MediaPlay"                 :media-play
   "MediaPlayPause"            :media-play-pause
   "MediaRecord"               :media-record
   "MediaRewind"               :media-rewind
   "MediaStop"                 :media-stop
   "MediaTrackNext"            :media-track-next
   "MediaTrackPrevious"        :media-track-previous
   "New"                       :new
   "Open"                      :open
   "Print"                     :print
   "Save"                      :save
   "SpellCheck"                :spell-check
   ;; Multimedia numpad keys
   "Key11"                     :key-11
   "Key12"                     :key-12
   ;; Audio keys
   "AudioBalanceLeft"          :audio-balance-left
   "AudioBalanceRight"         :audio-balance-right
   "AudioBassBoostDown"        :audio-bass-boost-down
   "AudioBassBoostToggle"      :audio-bass-boost-toggle
   "AudioBassBoostUp"          :audio-bass-boost-up
   "AudioFaderFront"           :audio-fader-front
   "AudioFaderRear"            :audio-fader-rear
   "AudioSurroundModeNext"     :audio-surround-mode-next
   "AudioTrebleDown"           :audio-treble-down
   "AudioTrebleUp"             :audio-treble-up
   "AudioVolumeDown"           :audio-volume-down
   "AudioVolumeUp"             :audio-volume-up
   "AudioVolumeMute"           :audio-volume-mute
   "MicrophoneToggle"          :microphone-toggle
   "MicrophoneVolumeDown"      :microphone-volume-down
   "MicrophoneVolumeUp"        :microphone-volume-up
   "MicrophoneVolumeMute"      :microphone-volume-mute
   ;; Speech keys
   "SpeechCorrectionList"      :speech-correction-list
   "SpeechInputToggle"         :speech-input-toggle
   ;; Application keys
   "LaunchApplication1"        :launch-application-1
   "LaunchApplication2"        :launch-application-2
   "LaunchCalendar"            :launch-calendar
   "LaunchContacts"            :launch-contacts
   "LaunchMail"                :launch-mail
   "LaunchMediaPlayer"         :launch-media-player
   "LaunchMusicPlayer"         :launch-music-player
   "LaunchPhone"               :launch-phone
   "LaunchScreenSaver"         :launch-screen-saver
   "LaunchSpreadsheet"         :launch-spreadsheet
   "LaunchWebBrowser"          :launch-web-browser
   "LaunchWebCam"              :launch-webcam
   "LaunchWordProcessor"       :launch-word-processor
   ;; Browser keys
   "BrowserBack"               :browser-back
   "BrowserFavorites"          :browser-favorites
   "BrowserForward"            :browser-forward
   "BrowserHome"               :browser-home
   "BrowserRefresh"            :browser-refresh
   "BrowserSearch"             :browser-search
   "BrowserStop"               :browser-stop
   ;; Mobile phone keys
   "AppSwitch"                 :app-switch
   "Call"                      :call
   "Camera"                    :camera
   "CameraFocus"               :camera-focus
   "EndCall"                   :end-call
   "GoBack"                    :go-back
   "GoHome"                    :go-home
   "HeadsetHook"               :headset-hook
   "LastNumberRedial"          :last-number-redial
   "Notification"              :notification
   "MannerMode"                :manner-mode
   "VoiceDial"                 :voice-dial
   ;; TV keys
   "TV"                        :tv
   "TV3DMode"                  :tv-3d-mode
   "TVAntennaCable"            :tv-antenna-cable
   "TVAudioDescription"        :tv-audio-description
   "TVAudioDescriptionMixDown" :tv-audio-description-mix-down
   "TVAudioDescriptionMixUp"   :tv-audio-description-mix-up
   "TVContentsMenu"            :tv-contents-menu
   "TVInput"                   :tv-input
   "TVInputComponent1"         :tv-input-component-1
   "TVInputComponent2"         :tv-input-component-2
   "TVInputComposite1"         :tv-input-composite-1
   "TVInputComposite2"         :tv-input-composite-2
   "TVInputHDMI1"              :tv-input-hdmi-1
   "TVInputHDMI2"              :tv-input-hdmi-2
   "TVInputHDMI3"              :tv-input-hdmi-3
   "TVInputHDMI4"              :tv-input-hdmi-4
   "TVInputVGA1"               :tv-input-vga-1
   "TVMediaContext"            :tv-media-context
   "TVNetwork"                 :tv-network
   "TVNumberEntry"             :tv-number-entry
   "TVPower"                   :tv-power
   "TVRadioService"            :tv-radio-service
   "TVSatellite"               :tv-satellite
   "TVSatelliteBS"             :tv-satellite-bs
   "TVSatelliteCS"             :tv-satellite-cs
   "TVSatelliteToggle"         :tv-sattelite-toggle
   "TVTerrestrialAnalog"       :tv-terrestrial-analog
   "TVTerrestrialDigital"      :tv-terrestrial-digital
   "TVTimer"                   :tv-timer
   ;; Media controller keys
   "AVRInput"                  :avr-input
   "AVRPower"                  :avr-power
   "ColorF0Red"                :color-f0-red
   "ColorF1Green"              :color-f1-green
   "ColorF2Yellow"             :color-f2-yellow
   "ColorF3Blue"               :color-f3-blue
   "ColorF4Grey"               :color-f4-grey
   "ColorF5Brown"              :color-f5-brown
   "ClosedCaptionToggle"       :closed-caption-toggle
   "Dimmer"                    :dimmer
   "DisplaySwap"               :display-swap
   "DVR"                       :dvr
   "Exit"                      :exit
   "FavoriteClear0"            :favorite-clear-0
   "FavoriteClear1"            :favorite-clear-1
   "FavoriteClear2"            :favorite-clear-2
   "FavoriteClear3"            :favorite-clear-3
   "FavoriteRecall0"           :favorite-recall-0
   "FavoriteRecall1"           :favorite-recall-1
   "FavoriteRecall2"           :favorite-recall-2
   "FavoriteRecall3"           :favorite-recall-3
   "FavoriteStore0"            :favorite-store-0
   "FavoriteStore1"            :favorite-store-1
   "FavoriteStore2"            :favorite-store-2
   "FavoriteStore3"            :favorite-store-3
   "Guide"                     :guide
   "GuideNextDay"              :guide-next-day
   "GuidePreviousDay"          :guide-previous-day
   "Info"                      :info
   "InstantReplay"             :instant-replay
   "Link"                      :link
   "ListProgram"               :list-program
   "LiveContent"               :live-content
   "Lock"                      :lock
   "MediaApps"                 :media-apps
   "MediaAudioTrack"           :media-audio-track
   "MediaLast"                 :media-last
   "MediaSkipBackward"         :media-skip-backward
   "MediaSkipForward"          :media-skip-forward
   "MediaStepBackward"         :media-step-backward
   "MediaStepForward"          :media-step-forward
   "MediaTopMenu"              :media-top-menu
   "NavigateIn"                :navigate-in
   "NavigateNext"              :navigate-next
   "NavigateOut"               :navigate-out
   "NavigatePrevious"          :navigate-previous
   "NextFavoriteChannel"       :next-favorite-channel
   "NextUserProfile"           :next-user-profile
   "OnDemand"                  :on-demand
   "Pairing"                   :pairing
   "PinPDown"                  :pip-down
   "PinPMove"                  :pip-move
   "PinPToggle"                :pip-toggle
   "PinPUp"                    :pip-up
   "PlaySpeedDown"             :play-speed-down
   "PlaySpeedReset"            :play-speed-reset
   "PlaySpeedUp"               :play-speed-up
   "RandomToggle"              :random-toggle
   "RcLowBattery"              :rc-low-battery
   "RecordSpeedNext"           :record-speed-next
   "RfBypass"                  :rf-bypass
   "ScanChannelsToggle"        :scan-channels-toggle
   "ScreenModeNext"            :screen-mode-next
   "Settings"                  :settings
   "SplitScreenToggle"         :split-screen-toggle
   "STBInput"                  :stb-input
   "STBPower"                  :stb-power
   "Subtitle"                  :subtitle
   "Teletext"                  :teletext
   "VideoModeNext"             :video-mode-next
   "Wink"                      :wink
   "ZoomToggle"                :zoom-toggle})

(defn key-ident>label [ident] (get key-ident->label ident ident))

(def label->key-ident (set/map-invert key-ident->label))

(defn label>key-ident [label] (get label->key-ident label label))
