package internal.skills.controller;

import internal.skills.service.ApiKeys;
import internal.skills.dto.ApiKeyContracts;
import internal.skills.dto.Contracts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/admin/api-keys")
public class ApiKeyController {
    private final ApiKeys keys;
    public ApiKeyController(ApiKeys keys) { this.keys=keys; }
    @GetMapping Contracts.Page<ApiKeyContracts.Metadata> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return keys.list(page,pageSize); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ApiKeyContracts.Created create(@Valid @RequestBody ApiKeyContracts.Input input) { return keys.create(input); }
    @PostMapping("/{id}/revoke") ApiKeyContracts.Metadata revoke(@PathVariable @Min(1) long id) { return keys.revoke(id); }
}
