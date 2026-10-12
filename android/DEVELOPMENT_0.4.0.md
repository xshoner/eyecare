# EyeCare 0.4.0-alpha MVP development record

Built from the 0.3.1-alpha source in an isolated copy while the original `D:\Neurolens2` checkout remained untouched during development. The objective is to collect feasible signals actively while preserving observed value, quality, availability, missing reason and estimation status.

The implementation adds `ScreenSignalEngine`, `SensorHub`, `EyeSignalEngine`, `MvpScorer`, and a local `PersonalBaselineRepository`. It connects them to the existing foreground capture service, reports, local event storage, Android permission flow and report UI. The Screen RGB family S01–S17 is present, except unsupported Night Light/Extra Dim readback which remains explicitly missing. Camera data includes full available blendshape coefficients, selected landmarks and a face-light proxy without storing source images. Optional motion permission is requested at the start; Usage Access remains a separate user-controlled setting.

This version is a debug research alpha. See `VALIDATION_0.4.0.md` for executed checks and outstanding hardware/clinical validation. Broader PRD items such as calibrated distance, rPPG, redness and a learned model remain future work.
