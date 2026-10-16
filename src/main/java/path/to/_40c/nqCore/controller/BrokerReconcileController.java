package path.to._40c.nqCore.controller;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import path.to._40c.nqCore.service.BrokerPositionReconciler;
import path.to._40c.nqCore.service.BrokerPositionReconciler.Mismatch;

@RestController
@RequestMapping("/api")
@Slf4j
public class BrokerReconcileController {
    private final BrokerPositionReconciler reconciler;

    public BrokerReconcileController(BrokerPositionReconciler reconciler) {
        this.reconciler = reconciler;
    }

    /**
     * Runs the broker-vs-app position check now and returns the mismatches — an empty list means
     * the broker's net NIFTY positions equal the app's LIVE legs. Read-only: nothing is traded.
     *
     * Example: GET /api/broker-positions/reconcile
     */
    @GetMapping("/broker-positions/reconcile")
    public List<Mismatch> reconcileNow() {
        log.info("Broker position reconcile requested via API");
        return reconciler.reconcile();
    }
}
