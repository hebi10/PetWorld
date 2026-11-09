package com.haebi.petworld.data.room

import com.haebi.petworld.data.NeuterStatus
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.data.PetSex
import com.haebi.petworld.data.PetSpecies

fun PetEntity.toDomain() = PetProfile(
    id = id,
    name = name,
    species = PetSpecies.valueOf(species),
    breed = breed,
    birthday = birthday,
    estimatedAgeYears = estimatedAgeYears,
    sex = PetSex.valueOf(sex),
    neuterStatus = NeuterStatus.valueOf(neuterStatus),
    notes = notes,
    photoFile = photoFile,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PetProfile.toEntity() = PetEntity(
    id = id,
    name = name,
    species = species.name,
    breed = breed,
    birthday = birthday,
    estimatedAgeYears = estimatedAgeYears,
    sex = sex.name,
    neuterStatus = neuterStatus.name,
    notes = notes,
    photoFile = photoFile,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
