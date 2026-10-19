package internal.skills.controller;

import internal.skills.service.SkillService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


import static internal.skills.dto.Contracts.*;

@RestController
@Validated
@RequestMapping("/api/admin/skills")
public class AdminController {
    private final SkillService service;

    public AdminController(SkillService service) {
        this.service = service;
    }

    @GetMapping
    Page<Admin> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                     @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.adminList(keyword, status, page, pageSize);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Admin create(@Valid @RequestBody Create input) {
        return service.create(input);
    }

    @GetMapping("/{id}")
    Admin detail(@PathVariable @Min(1) long id) {
        return service.detail(id);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Admin save(@PathVariable @Min(1) long id, @Valid @RequestBody Update input) {
        return service.save(id, input);
    }

    @PostMapping(value = "/{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    Admin publish(@PathVariable @Min(1) long id, @Valid @RequestBody Version input) {
        return service.publish(id, input.lockVersion());
    }

    @PostMapping(value = "/{id}/unpublish", consumes = MediaType.APPLICATION_JSON_VALUE)
    Admin unpublish(@PathVariable @Min(1) long id, @Valid @RequestBody Version input) {
        return service.unpublish(id, input.lockVersion());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable @Min(1) long id, @RequestParam @Min(0) long lockVersion) {
        service.delete(id, lockVersion);
    }
}
