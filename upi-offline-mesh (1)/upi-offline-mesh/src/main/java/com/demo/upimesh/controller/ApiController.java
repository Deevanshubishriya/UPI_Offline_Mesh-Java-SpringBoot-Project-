package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ServerKeyHolder serverKeyHolder;
    private final DemoService demoService;
    private final MeshSimulatorService meshSimulatorService;
    private final BridgeIngestionService bridgeIngestionService;
    private final IdempotencyService idempotencyService;

    public ApiController(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          ServerKeyHolder serverKeyHolder,
                          DemoService demoService,
                          MeshSimulatorService meshSimulatorService,
                          BridgeIngestionService bridgeIngestionService,
                          IdempotencyService idempotencyService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.serverKeyHolder = serverKeyHolder;
        this.demoService = demoService;
        this.meshSimulatorService = meshSimulatorService;
        this.bridgeIngestionService = bridgeIngestionService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/server-key")
    public Map<String, String> serverKey() {
        return Map.of("publicKeyBase64", serverKeyHolder.getPublicKeyBase64());
    }

    @GetMapping("/accounts")
    public List<Account> accounts() {
        return accountRepository.findAll();
    }

    @GetMapping("/transactions")
    public List<Transaction> transactions() {
        return transactionRepository.findTop20ByOrderBySettledAtDesc();
    }

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        Map<String, Object> state = new LinkedHashMap<>();
        meshSimulatorService.getDevices().forEach((id, device) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("hasInternet", device.hasInternet());
            info.put("packetCount", device.packetCount());
            info.put("packets", device.getPackets().values().stream()
                    .map(p -> Map.of(
                            "packetId", p.getPacketId(),
                            "ttl", p.getTtl(),
                            "createdAt", p.getCreatedAt()))
                    .collect(Collectors.toList()));
            state.put(id, info);
        });
        state.put("idempotencyCacheSize", idempotencyService.size());
        return state;
    }

    public record SendPaymentRequest(String senderVpa, String receiverVpa, BigDecimal amount, String pin) {}

    @PostMapping("/demo/send")
    public Map<String, Object> demoSend(@RequestBody SendPaymentRequest request) {
        MeshPacket packet = demoService.sendPayment(
                request.senderVpa(), request.receiverVpa(), request.amount(), request.pin());
        return Map.of(
                "packetId", packet.getPacketId(),
                "ttl", packet.getTtl(),
                "injectedInto", "phone-alice"
        );
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> gossip() {
        meshSimulatorService.gossipRound();
        return meshState();
    }

    @PostMapping("/mesh/flush")
    public List<IngestResult> flush() {
        return meshSimulatorService.flushBridges();
    }

    @PostMapping("/mesh/reset")
    public Map<String, String> reset() {
        meshSimulatorService.reset();
        idempotencyService.reset();
        return Map.of("status", "reset");
    }

    @PostMapping("/bridge/ingest")
    public IngestResult bridgeIngest(@RequestBody MeshPacket packet,
                                      @RequestHeader(value = "X-Bridge-Node-Id", required = false) String bridgeNodeId,
                                      @RequestHeader(value = "X-Hop-Count", required = false) Integer hopCount) {
        return bridgeIngestionService.ingest(packet);
    }
}
