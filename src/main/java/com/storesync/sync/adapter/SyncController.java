package com.storesync.sync.adapter;

import com.storesync.shared.security.Caller;
import com.storesync.sync.application.SyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SyncController {

    private final SyncService sync;

    SyncController(SyncService sync) {
        this.sync = sync;
    }

    @PostMapping("/v1/sync/push")
    SyncService.PushResult push(Caller caller, @RequestBody SyncService.Push body) {
        return sync.push(caller, body);
    }

    @GetMapping("/v1/sync/pull")
    SyncService.PullResult pull(Caller caller, @RequestParam(defaultValue = "0") long since) {
        return sync.pull(caller, since);
    }
}
