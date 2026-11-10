'use strict';
    let scheduleSettingsDraft = null;
    function settingsTarget() { return scheduleSettingsDraft?.settings || state.settings; }
    function withSettingsDraft(action) {
        if (!scheduleSettingsDraft) return action();
        const manual = state.settings;
        state.settings = scheduleSettingsDraft.settings;
        try { return action(); }
        finally { state.settings = manual; }
    }
