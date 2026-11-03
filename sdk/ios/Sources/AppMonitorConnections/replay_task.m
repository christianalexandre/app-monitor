#import "AppMonitorConnections.h"

// ADR-0186: no Swift frame may sit between the raise and this catch.
NSURLSessionTask *appmonitor_replay_task(
    NSURLSession *session, NSURLRequest *request, NSData *data, NSURL *file, appmonitor_task_done done) {
    @try {
        if (data != nil) return [session uploadTaskWithRequest:request fromData:data completionHandler:done];
        if (file != nil) return [session uploadTaskWithRequest:request fromFile:file completionHandler:done];
        return [session dataTaskWithRequest:request completionHandler:done];
    } @catch (NSException *exception) {
        return nil;
    }
}
